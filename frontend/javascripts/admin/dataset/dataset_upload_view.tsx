import {
  Popover,
  Avatar,
  Form,
  Button,
  Col,
  Row,
  Tooltip,
  Modal,
  Progress,
  Alert,
  List,
  Spin,
} from "antd";
import { Location as HistoryLocation, Action as HistoryAction } from "history";
import { InfoCircleOutlined, FileOutlined, FolderOutlined, InboxOutlined } from "@ant-design/icons";
import { connect } from "react-redux";
import React from "react";
import moment from "moment";

import classnames from "classnames";
import _ from "lodash";
import { useDropzone, FileWithPath } from "react-dropzone";
import ErrorHandling from "libs/error_handling";
import type { RouteComponentProps } from "react-router-dom";
import { withRouter } from "react-router-dom";
import type { APITeam, APIDataStore, APIUser, APIDatasetId } from "types/api_flow_types";
import type { OxalisState } from "oxalis/store";
import {
  reserveDatasetUpload,
  finishDatasetUpload,
  cancelDatasetUpload,
  createResumableUpload,
  startConvertToWkwJob,
  sendAnalyticsEvent,
  sendFailedRequestAnalyticsEvent,
} from "admin/admin_rest_api";
import Toast from "libs/toast";
import * as Utils from "libs/utils";
import messages from "messages";
import { trackAction } from "oxalis/model/helpers/analytics";
// @ts-expect-error ts-migrate(2306) FIXME: File ... Remove this comment to see the full error message
import { createReader, BlobReader, ZipReader, Entry } from "zip-js-webpack";
import {
  CardContainer,
  DatasetNameFormItem,
  DatastoreFormItem,
} from "admin/dataset/dataset_components";
import { Vector3Input } from "libs/vector_input";
import TeamSelectionComponent from "dashboard/dataset/team_selection_component";
import features from "features";
import { syncValidator } from "types/validation";
import { FormInstance } from "antd/lib/form";
import type { Vector3 } from "oxalis/constants";
import { FormItemWithInfo, confirmAsync } from "../../dashboard/dataset/helper_components";
const FormItem = Form.Item;
const REPORT_THROTTLE_THRESHOLD = 1 * 60 * 1000; // 1 min

const logRetryToAnalytics = _.throttle((datasetName: string) => {
  ErrorHandling.notify(new Error(`Warning: Upload of dataset ${datasetName} was retried.`));
}, REPORT_THROTTLE_THRESHOLD);

type OwnProps = {
  datastores: Array<APIDataStore>;
  withoutCard?: boolean;
  onUploaded: (arg0: string, arg1: string, arg2: boolean) => Promise<void> | void;
};
type StateProps = {
  activeUser: APIUser | null | undefined;
};
type Props = OwnProps & StateProps;
type PropsWithFormAndRouter = Props & {
  history: RouteComponentProps["history"];
};
type State = {
  isUploading: boolean;
  isFinishing: boolean;
  needsConversion: boolean;
  isRetrying: boolean;
  uploadProgress: number;
  selectedTeams: APITeam | Array<APITeam>;
  uploadId: string;
  resumableUpload: any;
  datastoreUrl: string;
};

function WkwExample() {
  const description = `
  great_dataset          # Root folder
  ├─ color               # Dataset layer (e.g., color, segmentation)
  │  ├─ 1                # Magnification step (1, 2, 4, 8, 16 etc.)
  │  │  ├─ header.wkw    # Header wkw file
  │  │  ├─ z0
  │  │  │  ├─ y0
  │  │  │  │  ├─ x0.wkw  # Actual data wkw file
  │  │  │  │  └─ x1.wkw  # Actual data wkw file
  │  │  │  └─ y1/...
  │  │  └─ z1/...
  │  └─ 2/...
  ├─ segmentation/...
  └─ datasource-properties.json  # Dataset metadata (will be created upon import, if non-existent)
  `;
  return (
    <div>
      <h4>A typical WKW dataset looks like this:</h4>
      <pre className="dataset-import-folder-structure-hint">{description}</pre>
    </div>
  );
}

function SingleLayerImageStackExample() {
  const description = `
  great_dataset          # Root folder or zip archive (this outer container be omitted)
  ├─ file1.tif           # The files don't have to follow a certain naming pattern.
  ├─ file2.tif           # However, the files are sorted to obtain the final z-order.
  └─ file3.tif
  `;
  return (
    <div>
      <h4>For example, a flat list of (sorted) image files can be imported:</h4>
      <pre className="dataset-import-folder-structure-hint">{description}</pre>
    </div>
  );
}

function MultiLayerImageStackExample() {
  const description = `
  great_dataset          # Root folder or zip archive (this outer container be omitted)
  ├─ color               # 1st dataset layer (name may be arbitrary, e.g., color or segmentation)
  │  ├─ file1.tif        # The files don't have to follow a certain naming pattern.
  │  ├─ file2.tif        # However, the files are sorted to obtain the final z-order.
  │  └─ file3.tif
  └─ segmentation        # 2nd dataset layer
     ├─ file1.tif
     ├─ file2.tif
     └─ file3.tif
  `;
  return (
    <div>
      <h4>Uploading multiple image stacks (one per folder) will create a multi-layer dataset:</h4>
      <pre className="dataset-import-folder-structure-hint">{description}</pre>
    </div>
  );
}

class DatasetUploadView extends React.Component<PropsWithFormAndRouter, State> {
  state: State = {
    isUploading: false,
    isFinishing: false,
    needsConversion: false,
    isRetrying: false,
    uploadProgress: 0,
    selectedTeams: [],
    uploadId: "",
    resumableUpload: {},
    datastoreUrl: "",
  };

  unblock: ((...args: Array<any>) => any) | null | undefined;
  blockTimeoutId: number | null = null;
  formRef = React.createRef<FormInstance>();

  componentDidMount() {
    sendAnalyticsEvent("open_upload_view");
  }

  componentDidUpdate(prevProps: PropsWithFormAndRouter) {
    const uploadableDatastores = this.props.datastores.filter(
      (datastore) => datastore.allowsUpload,
    );
    const currentFormRef = this.formRef.current;

    if (currentFormRef != null) {
      const selectedDataStoreUrl = currentFormRef.getFieldValue("datastoreUrl");

      if (
        prevProps.datastores.length === 0 &&
        uploadableDatastores.length > 0 &&
        (selectedDataStoreUrl == null || selectedDataStoreUrl !== uploadableDatastores[0].url)
      ) {
        currentFormRef.setFieldsValue({
          datastoreUrl: uploadableDatastores[0].url,
        });
      }
    }
  }

  componentWillUnmount() {
    this.unblockHistory();
  }

  unblockHistory() {
    window.onbeforeunload = null;

    if (this.blockTimeoutId != null) {
      clearTimeout(this.blockTimeoutId);
      this.blockTimeoutId = null;
    }

    if (this.unblock != null) {
      this.unblock();
    }
  }

  getDatastoreForUrl(url: string): APIDataStore | null | undefined {
    const uploadableDatastores = this.props.datastores.filter(
      (datastore) => datastore.allowsUpload,
    );
    return uploadableDatastores.find((ds) => ds.url === url);
  }

  handleSubmit = async (formValues: Record<string, any>) => {
    const { activeUser } = this.props;

    if (activeUser != null) {
      Toast.info("Uploading dataset");
      this.setState({
        isUploading: true,
      });

      const beforeUnload = (
        newLocation: HistoryLocation<unknown>,
        action: HistoryAction,
      ): string | false | void => {
        // Only show the prompt if this is a proper beforeUnload event from the browser
        // or the pathname changed
        // This check has to be done because history.block triggers this function even if only the url hash changed
        if (action === undefined || newLocation.pathname !== window.location.pathname) {
          const { isUploading } = this.state;

          if (isUploading) {
            window.onbeforeunload = null; // clear the event handler otherwise it would be called twice. Once from history.block once from the beforeunload event

            this.blockTimeoutId = window.setTimeout(() => {
              // restore the event handler in case a user chose to stay on the page
              // @ts-ignore
              window.onbeforeunload = beforeUnload;
            }, 500);
            return messages["dataset.leave_during_upload"];
          }
        }

        // eslint-disable-next-line no-useless-return, consistent-return
        return;
      };

      this.unblock = this.props.history.block(beforeUnload);
      // @ts-ignore
      window.onbeforeunload = beforeUnload;
      const datasetId: APIDatasetId = {
        name: formValues.name,
        owningOrganization: activeUser.organization,
      };

      const getRandomString = () => {
        const randomBytes = window.crypto.getRandomValues(new Uint8Array(6));
        return Array.from(randomBytes, (byte) => `0${byte.toString(16)}`.slice(-2)).join("");
      };

      const uploadId = `${moment(Date.now()).format("YYYY-MM-DD_HH-mm")}__${
        datasetId.name
      }__${getRandomString()}`;
      const reserveUploadInformation = {
        uploadId,
        organization: datasetId.owningOrganization,
        name: datasetId.name,
        totalFileCount: formValues.zipFile.length,
        layersToLink: [],
        initialTeams: formValues.initialTeams.map((team: APITeam) => team.id),
      };
      const datastoreUrl = formValues.datastoreUrl;
      await reserveDatasetUpload(datastoreUrl, reserveUploadInformation);
      const resumableUpload = await createResumableUpload(datastoreUrl, uploadId);
      this.setState({
        uploadId,
        resumableUpload,
        datastoreUrl,
      });
      resumableUpload.on("complete", () => {
        const newestForm = this.formRef.current;

        if (!newestForm) {
          throw new Error("Form couldn't be initialized.");
        }

        const uploadInfo = {
          uploadId,
          needsConversion: this.state.needsConversion,
        };
        this.setState({
          isFinishing: true,
        });
        finishDatasetUpload(datastoreUrl, uploadInfo).then(
          async () => {
            trackAction("Upload dataset");
            await Utils.sleep(3000); // wait for 3 seconds so the server can catch up / do its thing

            Toast.success(messages["dataset.upload_success"]);
            let maybeError;

            if (this.state.needsConversion) {
              try {
                const datastore = this.getDatastoreForUrl(datastoreUrl);

                if (!datastore) {
                  throw new Error("Selected datastore does not match available datastores");
                }

                await startConvertToWkwJob(
                  formValues.name,
                  activeUser.organization,
                  formValues.scale,
                );
              } catch (error) {
                maybeError = error;
              }

              if (maybeError == null) {
                Toast.info(
                  <React.Fragment>
                    The conversion for the uploaded dataset was started.
                    <br />
                    See{" "}
                    <a target="_blank" href="/jobs" rel="noopener noreferrer">
                      Processing Jobs
                    </a>{" "}
                    for an overview of running jobs.
                  </React.Fragment>,
                );
              } else {
                Toast.error(
                  "The conversion for the uploaded dataset could not be started. Please try again or contact us if this issue occurs again.",
                );
              }
            }

            this.setState({
              isUploading: false,
              isFinishing: false,
            });

            if (maybeError == null) {
              newestForm.setFieldsValue({
                name: null,
                zipFile: [],
              });
              this.props.onUploaded(
                activeUser.organization,
                formValues.name,
                this.state.needsConversion,
              );
            }
          },
          (error) => {
            sendFailedRequestAnalyticsEvent("finish_dataset_upload", error, {
              dataset_name: datasetId.name,
            });
            Toast.error(messages["dataset.upload_failed"]);
            this.setState({
              isUploading: false,
              isFinishing: false,
              isRetrying: false,
              uploadProgress: 0,
            });
          },
        );
      });
      resumableUpload.on("filesAdded", () => {
        resumableUpload.upload();
      });
      resumableUpload.on("fileError", (_file: FileWithPath, message: string) => {
        Toast.error(message);
        this.setState({
          isUploading: false,
        });
      });
      resumableUpload.on("progress", () => {
        this.setState({
          isRetrying: false,
          uploadProgress: resumableUpload.progress(),
        });
      });
      resumableUpload.on("fileRetry", () => {
        logRetryToAnalytics(datasetId.name);
        this.setState({
          isRetrying: true,
        });
      });
      resumableUpload.addFiles(formValues.zipFile);
    }
  };

  cancelUpload = async () => {
    const { uploadId, resumableUpload, datastoreUrl } = this.state;
    resumableUpload.pause();
    const shouldCancel = await confirmAsync({
      title:
        "Cancelling the running upload will delete already uploaded files on the server and cannot be undone. Are you sure you want to cancel the upload?",
      okText: "Yes, cancel the upload",
      cancelText: "No, keep it running",
    });

    if (!shouldCancel) {
      resumableUpload.upload();
      return;
    }

    resumableUpload.cancel();
    await cancelDatasetUpload(datastoreUrl, {
      uploadId,
    });
    this.setState({
      isUploading: false,
      isFinishing: false,
      isRetrying: false,
      uploadProgress: 0,
    });
    Toast.success(messages["dataset.upload_cancel"]);
  };

  getUploadModal = () => {
    const form = this.formRef.current;

    if (!form) {
      return null;
    }

    const { isRetrying, isFinishing, uploadProgress, isUploading } = this.state;
    return (
      <Modal
        visible={isUploading}
        keyboard={false}
        maskClosable={false}
        className="no-footer-modal"
        okButtonProps={{
          style: {
            display: "none",
          },
        }}
        cancelButtonProps={{
          style: {
            display: "none",
          },
        }}
        onCancel={this.cancelUpload}
      >
        <div
          style={{
            display: "flex",
            alignItems: "center",
            flexDirection: "column",
          }}
        >
          <FolderOutlined
            style={{
              fontSize: 50,
            }}
          />
          <br />
          {isRetrying
            ? `Upload of dataset ${form.getFieldValue("name")} froze.`
            : `Uploading Dataset ${form.getFieldValue("name")}.`}
          <br />
          {isRetrying ? "Retrying to continue the upload …" : null}
          <br />
          <Progress // Round to 1 digit after the comma.
            percent={Math.round(uploadProgress * 1000) / 10}
            status="active"
          />
          {isFinishing ? <Spin tip="Processing uploaded files …" /> : null}
        </div>
      </Modal>
    );
  };

  validateFiles = (files: FileWithPath[]) => {
    if (files.length === 0) {
      return;
    }

    let needsConversion = true;
    const fileExtensions = [];

    for (const file of files) {
      const filenameParts = file.name.split(".");
      const fileExtension = filenameParts[filenameParts.length - 1].toLowerCase();
      fileExtensions.push(fileExtension);
      sendAnalyticsEvent("add_files_to_upload", {
        fileExtension,
      });

      if (fileExtension === "zip") {
        createReader(
          new BlobReader(file),
          (reader: ZipReader) => {
            reader.getEntries((entries: Array<Entry>) => {
              const wkwFile = entries.find((entry: Entry) =>
                Utils.isFileExtensionEqualTo(entry.filename, "wkw"),
              );
              const hasArchiveWkwFile = wkwFile != null;
              this.handleNeedsConversionInfo(!hasArchiveWkwFile);
            });
          },
          () => {
            Modal.error({
              content: messages["dataset.upload_invalid_zip"],
            });
            const form = this.formRef.current;

            if (!form) {
              return;
            }

            form.setFieldsValue({
              zipFile: [],
            });
          },
        );
        // We return here since not more than 1 zip archive is supported anyway.
        return;
      } else if (fileExtension === "wkw") {
        needsConversion = false;
      }
    }

    const countedFileExtensions = _.countBy(fileExtensions, (str) => str);

    Object.entries(countedFileExtensions).map(([fileExtension, count]) =>
      sendAnalyticsEvent("add_files_to_upload", {
        fileExtension,
        count,
      }),
    );
    this.handleNeedsConversionInfo(needsConversion);
  };

  handleNeedsConversionInfo = (needsConversion: boolean) => {
    const form = this.formRef.current;

    if (!form) {
      return;
    }

    this.setState({
      needsConversion,
    });

    if (needsConversion && !features().jobsEnabled) {
      form.setFieldsValue({
        zipFile: [],
      });
      Modal.info({
        content: (
          <div>
            The selected dataset does not seem to be in the WKW format. Please convert the dataset
            using{" "}
            <a
              target="_blank"
              href="https://github.com/scalableminds/webknossos-cuber/"
              rel="noopener noreferrer"
            >
              webknossos-cuber
            </a>{" "}
            or use a webKnossos instance which integrates a conversion service, such as{" "}
            <a target="_blank" href="http://webknossos.org/" rel="noopener noreferrer">
              webknossos.org
            </a>
            .
          </div>
        ),
      });
    }
  };

  maybeSetUploadName = (files: FileWithPath[]) => {
    const form = this.formRef.current;

    if (!form) {
      return;
    }

    if (!form.getFieldValue("name") && files.length > 0) {
      const filenameParts = files[0].name.split(".");
      const filename = filenameParts.slice(0, -1).join(".");
      form.setFieldsValue({
        name: filename,
      });
      form.validateFields(["name"]);
    }
  };

  render() {
    const form = this.formRef.current;
    const { activeUser, withoutCard, datastores } = this.props;
    const isDatasetManagerOrAdmin = Utils.isUserAdminOrDatasetManager(this.props.activeUser);

    const { needsConversion } = this.state;
    const uploadableDatastores = datastores.filter((datastore) => datastore.allowsUpload);
    const hasOnlyOneDatastoreOrNone = uploadableDatastores.length <= 1;
    return (
      <div
        className="dataset-administration"
        style={{
          padding: 5,
        }}
      >
        <CardContainer withoutCard={withoutCard} title="Upload Dataset">
          <Form
            onFinish={this.handleSubmit}
            layout="vertical"
            ref={this.formRef}
            initialValues={{
              initialTeams: [],
              scale: [0, 0, 0],
              zipFile: [],
            }}
          >
            {features().isDemoInstance && (
              <Alert
                message={
                  <>
                    We are happy to help!
                    <br />
                    Please <a href="mailto:hello@webknossos.org">contact us</a> if you have any
                    trouble uploading your data or the uploader doesn&apos;t support your format
                    yet.
                  </>
                }
                type="info"
                style={{
                  marginBottom: 50,
                }}
              />
            )}
            <Row gutter={8}>
              <Col span={12}>
                <DatasetNameFormItem activeUser={activeUser} />
              </Col>
              <Col span={12}>
                <FormItem
                  name="initialTeams"
                  label="Teams allowed to access this dataset"
                  hasFeedback
                  rules={[
                    {
                      required: !isDatasetManagerOrAdmin,
                      // @ts-expect-error ts-migrate(2322) FIXME: Type 'string | null' is not assignable to type 'st... Remove this comment to see the full error message
                      message: !isDatasetManagerOrAdmin
                        ? messages["dataset.import.required.initialTeam"]
                        : null,
                    },
                  ]}
                >
                  <Tooltip title="Except for administrators and dataset managers, only members of the teams defined here will be able to view this dataset.">
                    <TeamSelectionComponent
                      mode="multiple"
                      value={this.state.selectedTeams}
                      allowNonEditableTeams={isDatasetManagerOrAdmin}
                      onChange={(selectedTeams) => {
                        if (this.formRef.current == null) return;

                        if (!Array.isArray(selectedTeams)) {
                          // Making sure that we always have an array even when only one team is selected.
                          selectedTeams = [selectedTeams];
                        }

                        this.formRef.current.setFieldsValue({
                          initialTeams: selectedTeams,
                        });
                        this.setState({
                          selectedTeams,
                        });
                      }}
                      afterFetchedTeams={(fetchedTeams) => {
                        if (!features().isDemoInstance) {
                          return;
                        }

                        const teamOfOrganisation = fetchedTeams.find(
                          (team) => team.name === team.organization,
                        );

                        if (teamOfOrganisation == null) {
                          return;
                        }

                        if (this.formRef.current == null) return;
                        this.formRef.current.setFieldsValue({
                          initialTeams: [teamOfOrganisation],
                        });
                        this.setState({
                          selectedTeams: [teamOfOrganisation],
                        });
                      }}
                    />
                  </Tooltip>
                </FormItem>
              </Col>
            </Row>
            <DatastoreFormItem
              // @ts-expect-error ts-migrate(2322) FIXME: Type '{ form: FormInstance<any> | null; datastores... Remove this comment to see the full error message
              form={form}
              datastores={uploadableDatastores}
              hidden={hasOnlyOneDatastoreOrNone}
            />
            {features().jobsEnabled && needsConversion ? (
              <FormItemWithInfo
                name="scale"
                label="Voxel Size"
                info="The voxel size defines the extent (for x, y, z) of one voxel in nanometer."
                // @ts-ignore
                disabled={this.state.needsConversion}
                help="Your dataset is not yet in WKW Format. Therefore you need to define the voxel size."
                rules={[
                  {
                    required: this.state.needsConversion,
                    message: "Please provide a scale for the dataset.",
                  },
                  {
                    validator: syncValidator(
                      (value: Vector3) => value?.every((el) => el > 0),
                      "Each component of the scale must be larger than 0.",
                    ),
                  },
                ]}
              >
                <Vector3Input
                  style={{
                    width: 400,
                  }}
                  allowDecimals
                  onChange={(scale) => {
                    if (this.formRef.current == null) return;
                    this.formRef.current.setFieldsValue({
                      scale,
                    });
                  }}
                />
              </FormItemWithInfo>
            ) : null}

            <FormItem
              name="zipFile"
              label="Dataset"
              hasFeedback
              rules={[
                {
                  required: true,
                  message: messages["dataset.import.required.zipFile"],
                },
                {
                  validator: syncValidator(
                    (files: FileWithPath[]) =>
                      files.filter((file) => Utils.isFileExtensionEqualTo(file.path || "", "zip"))
                        .length <= 1,
                    "You cannot upload more than one archive.",
                  ),
                },
                {
                  validator: syncValidator(
                    (files: FileWithPath[]) =>
                      files.filter((file) =>
                        Utils.isFileExtensionEqualTo(file.path, ["tar", "rar", "gz"]),
                      ).length === 0,
                    "Tar, tar.gz and rar archives are not supported currently. Please use zip archives.",
                  ),
                },
                {
                  validator: syncValidator(
                    (files: FileWithPath[]) =>
                      files.filter((file) =>
                        Utils.isFileExtensionEqualTo(file.path, ["ply", "stl", "obj"]),
                      ).length === 0,
                    "PLY, STL and OBJ files are not supported. Please upload image files instead of 3D geometries.",
                  ),
                },
                {
                  validator: syncValidator(
                    (files: FileWithPath[]) =>
                      files.filter((file) => Utils.isFileExtensionEqualTo(file.path, ["mrc"]))
                        .length === 0,
                    "MRC files are not supported currently.",
                  ),
                },
                {
                  validator: syncValidator((files: FileWithPath[]) => {
                    const archives = files.filter((file) =>
                      Utils.isFileExtensionEqualTo(file.path, "zip"),
                    );
                    // Either there are no archives, or all files are archives
                    return archives.length === 0 || archives.length === files.length;
                  }, "Archives cannot be mixed with other files."),
                },
                {
                  validator: syncValidator((files: FileWithPath[]) => {
                    const wkwFiles = files.filter((file) =>
                      Utils.isFileExtensionEqualTo(file.path, "wkw"),
                    );
                    const imageFiles = files.filter((file) =>
                      Utils.isFileExtensionEqualTo(file.path, [
                        "tif",
                        "tiff",
                        "jpg",
                        "jpeg",
                        "png",
                        "czi",
                        "dm3",
                        "dm4",
                        "nifti",
                        "raw",
                      ]),
                    );
                    return wkwFiles.length === 0 || imageFiles.length === 0;
                  }, "WKW files should not be mixed with image files."),
                },
              ]}
              valuePropName="fileList"
            >
              <FileUploadArea
                onChange={(files: FileWithPath[]) => {
                  this.maybeSetUploadName(files);
                  this.validateFiles(files);
                }}
                fileList={[]}
              />
            </FormItem>
            <FormItem
              style={{
                marginBottom: 0,
              }}
            >
              <Button
                size="large"
                type="primary"
                htmlType="submit"
                style={{
                  width: "100%",
                }}
              >
                Upload
              </Button>
            </FormItem>
          </Form>
        </CardContainer>

        {this.getUploadModal()}
      </div>
    );
  }
}

function FileUploadArea({
  fileList,
  onChange,
}: {
  fileList: FileWithPath[];
  onChange: (files: FileWithPath[]) => void;
}) {
  const onDropAccepted = (acceptedFiles: FileWithPath[]) => {
    // file.path should be set by react-dropzone (which uses file-selector::toFileWithPath).
    onChange(_.uniqBy(fileList.concat(acceptedFiles), (file) => file.path));
  };

  const removeFile = (file: FileWithPath) => {
    onChange(_.without(fileList, file));
  };

  const { getRootProps, getInputProps, isDragActive, isDragAccept, isDragReject } = useDropzone({
    onDropAccepted,
  });
  const acceptedFiles = fileList;
  const files: React.ReactNode[] = acceptedFiles.map((file: FileWithPath) => (
    <li key={file.path}>{file.path}</li>
  ));
  const showSmallFileList = files.length > 10;
  const list = (
    <List
      itemLayout="horizontal"
      dataSource={acceptedFiles}
      size={showSmallFileList ? "small" : "default"}
      renderItem={(item: FileWithPath) => (
        <List.Item
          actions={[
            <a key="list-loadmore-edit" onClick={() => removeFile(item)}>
              remove
            </a>,
          ]}
        >
          <List.Item.Meta
            avatar={
              !showSmallFileList && (
                <Avatar>
                  <FileOutlined />
                </Avatar>
              )
            }
            title={
              <span>
                {showSmallFileList && <FileOutlined />}{" "}
                <span
                  style={{
                    color: "darkgrey",
                  }}
                >{`${item.path?.split("/").slice(0, -1).join("/")}/`}</span>
                {item.name}
              </span>
            }
          />
        </List.Item>
      )}
    />
  );
  return (
    <div>
      <div
        {...getRootProps({
          className: classnames("dataset-upload-dropzone", {
            "dataset-upload-dropzone-active": isDragActive,
            "dataset-upload-dropzone-accept": isDragAccept,
            "dataset-upload-dropzone-rejct": isDragReject,
          }),
        })}
      >
        <input {...getInputProps()} />
        <InboxOutlined
          style={{
            fontSize: 48,
            color: "var(--ant-primary)",
          }}
        />
        <p
          style={{
            maxWidth: 800,
            textAlign: "center",
            marginTop: 8,
          }}
        >
          Drag your file(s) to this area to upload them. Either add individual image files, a zip
          archive or a folder.{" "}
          {features().jobsEnabled ? (
            <>
              <br />
              <br />
              <div
                style={{
                  textAlign: "left",
                  display: "inline-block",
                }}
              >
                The following file formats are supported:
                <ul>
                  <li>
                    <Popover content={<WkwExample />} trigger="hover">
                      WKW dataset
                      <InfoCircleOutlined
                        style={{
                          marginLeft: 4,
                        }}
                      />
                    </Popover>
                  </li>

                  <li>
                    <Popover content={<SingleLayerImageStackExample />} trigger="hover">
                      Single-Layer Image File Sequence (tif, jpg, png, dm3, dm4)
                      <InfoCircleOutlined
                        style={{
                          marginLeft: 4,
                        }}
                      />
                    </Popover>
                  </li>

                  <li>
                    <Popover content={<MultiLayerImageStackExample />} trigger="hover">
                      Multi-Layer Image File Sequence
                      <InfoCircleOutlined
                        style={{
                          marginLeft: 4,
                        }}
                      />
                    </Popover>
                  </li>

                  <li>Single-file images (tif, czi, nifti, raw)</li>

                  <li>KNOSSOS file hierarchy</li>
                </ul>
                Have a look at{" "}
                <a href="https://docs.webknossos.org/webknossos/data_formats.html#conversion-with-webknossos-org">
                  our documentation
                </a>{" "}
                to learn more.
              </div>
            </>
          ) : null}
        </p>
      </div>

      {files.length > 0 ? (
        <div
          style={{
            marginTop: 8,
          }}
        >
          <h5>Files</h5>
          <div
            style={{
              maxHeight: 600,
              overflowY: "auto",
            }}
          >
            {list}
          </div>
        </div>
      ) : null}
    </div>
  );
}

const mapStateToProps = (state: OxalisState): StateProps => ({
  activeUser: state.activeUser,
});

const connector = connect(mapStateToProps);
export default connector(withRouter<RouteComponentProps & OwnProps, any>(DatasetUploadView));
