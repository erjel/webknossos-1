import { Button, Modal, Tag, Card, Row, Col, List } from "antd";
import {
  CheckCircleOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EyeOutlined,
  PlayCircleOutlined,
  RollbackOutlined,
  TeamOutlined,
  UserAddOutlined,
} from "@ant-design/icons";
import type { RouteComponentProps } from "react-router-dom";
import { Link, withRouter } from "react-router-dom";
// @ts-expect-error ts-migrate(7016) FIXME: Could not find a declaration file for module '@sca... Remove this comment to see the full error message
import { PropTypes } from "@scalableminds/prop-types";
import { connect } from "react-redux";
// @ts-expect-error ts-migrate(7016) FIXME: Could not find a declaration file for module 'reac... Remove this comment to see the full error message
import Markdown from "react-remarkable";
import * as React from "react";

import classNames from "classnames";
import type { APITaskWithAnnotation, APIUser, APIAnnotation } from "types/api_flow_types";
import { AsyncButton, AsyncLink } from "components/async_clickables";
import type { OxalisState } from "oxalis/store";
import {
  deleteAnnotation,
  resetAnnotation,
  finishTask,
  requestTask,
  peekNextTasks,
  downloadAnnotation,
} from "admin/admin_rest_api";
import { enforceActiveUser } from "oxalis/model/accessors/user_accessor";
import { getSkeletonDescriptor } from "oxalis/model/accessors/skeletontracing_accessor";
import { getVolumeDescriptors } from "oxalis/model/accessors/volumetracing_accessor";
import { handleGenericError } from "libs/error_handling";
import FormattedDate from "components/formatted_date";
import LinkButton from "components/link_button";
import Persistence from "libs/persistence";
import Request from "libs/request";
import Toast from "libs/toast";
import TransferTaskModal from "dashboard/transfer_task_modal";
import * as Utils from "libs/utils";
import messages from "messages";

const typeHint: APITaskWithAnnotation[] = [];
const pageLength: number = 1000;

export type TaskModeState = {
  tasks: Array<APITaskWithAnnotation>;
  loadedAllTasks: boolean;
  lastLoadedPage: number;
};
type OwnProps = {
  userId: string | null | undefined;
  isAdminView: boolean;
};
type StateProps = {
  activeUser: APIUser;
};
type Props = OwnProps & StateProps;
type PropsWithRouter = Props & {
  history: RouteComponentProps["history"];
};
type State = {
  showFinishedTasks: boolean;
  isLoading: boolean;
  isTransferModalVisible: boolean;
  currentAnnotationId: string | null | undefined;
  finishedModeState: TaskModeState;
  unfinishedModeState: TaskModeState;
};
const persistence = new Persistence<Pick<State, "showFinishedTasks">>(
  {
    showFinishedTasks: PropTypes.bool,
  },
  "dashboardTaskList",
);

const convertAnnotationToTaskWithAnnotationType = (
  annotation: APIAnnotation,
): APITaskWithAnnotation => {
  const { task } = annotation;

  if (!task) {
    // This should never be the case unless tasks were deleted in the DB.
    throw new Error(
      `[Dashboard Tasks] Annotation ${annotation.id} has no task assigned. Please inform your admin.`,
    );
  }

  if (!task.type) {
    // @ts-expect-error ts-migrate(2540) FIXME: Cannot assign to 'type' because it is a read-only ... Remove this comment to see the full error message
    task.type = {
      summary: `[deleted] ${annotation.typ}`,
      description: "",
      settings: {
        allowedModes: "",
      },
    };
  }

  const newTask: APITaskWithAnnotation = Object.assign({}, task, {
    annotation,
  });
  return newTask;
};

class DashboardTaskListView extends React.PureComponent<PropsWithRouter, State> {
  state: State = {
    showFinishedTasks: false,
    isLoading: false,
    isTransferModalVisible: false,
    currentAnnotationId: null,
    finishedModeState: {
      tasks: [],
      loadedAllTasks: false,
      lastLoadedPage: -1,
    },
    unfinishedModeState: {
      tasks: [],
      loadedAllTasks: false,
      lastLoadedPage: -1,
    },
  };

  componentDidMount() {
    // @ts-ignore
    this.setState(persistence.load(this.props.history));
    this.fetchNextPage(0);
  }

  componentDidUpdate() {
    persistence.persist(this.props.history, this.state);
  }

  getFinishVerb = () => (this.state.showFinishedTasks ? "Unfinished" : "Finished");
  getCurrentModeState = () =>
    this.state.showFinishedTasks ? this.state.finishedModeState : this.state.unfinishedModeState;

  setCurrentModeState = (modeShape: Partial<TaskModeState>) => {
    const { showFinishedTasks } = this.state;
    // @ts-expect-error ts-migrate(2345) FIXME: Argument of type '(prevState: Readonly<State>) => ... Remove this comment to see the full error message
    this.setState((prevState) => {
      const newSubState = {
        ...prevState[showFinishedTasks ? "finishedModeState" : "unfinishedModeState"],
        ...modeShape,
      };
      return {
        [showFinishedTasks ? "finishedModeState" : "unfinishedModeState"]: newSubState,
      };
    });
  };

  confirmFinish(task: APITaskWithAnnotation) {
    Modal.confirm({
      content: messages["annotation.finish"],
      onOk: async () => {
        const { annotation } = task;
        const changedAnnotationWithTask = await finishTask(annotation.id);
        const changedTask = convertAnnotationToTaskWithAnnotationType(changedAnnotationWithTask);
        this.setState((prevState) => {
          const newUnfinishedTasks = prevState.unfinishedModeState.tasks.filter(
            (t) => t.id !== task.id,
          );
          const newFinishedTasks = [changedTask].concat(prevState.finishedModeState.tasks);
          const newUnfinishedModeState = {
            ...prevState.unfinishedModeState,
            tasks: newUnfinishedTasks,
          };
          const newFinishedModeState = { ...prevState.finishedModeState, tasks: newFinishedTasks };
          return {
            unfinishedModeState: newUnfinishedModeState,
            finishedModeState: newFinishedModeState,
          };
        });
      },
    });
  }

  // @ts-expect-error ts-migrate(7006) FIXME: Parameter 'pageNumber' implicitly has an 'any' typ... Remove this comment to see the full error message
  fetchNextPage = async (pageNumber) => {
    // this refers not to the pagination of antd but to the pagination of querying data from SQL
    const isFinished = this.state.showFinishedTasks;
    const previousTasks = this.getCurrentModeState().tasks;
    const url = this.props.userId
      ? `/api/users/${
          this.props.userId
        }/tasks?isFinished=${isFinished.toString()}&pageNumber=${pageNumber}`
      : `/api/user/tasks?isFinished=${isFinished.toString()}&pageNumber=${pageNumber}`;

    try {
      this.setState({
        isLoading: true,
      });
      const annotationsWithTasks = await Request.receiveJSON(url);
      const tasks = annotationsWithTasks.map(convertAnnotationToTaskWithAnnotationType);
      this.setCurrentModeState({
        loadedAllTasks:
          annotationsWithTasks.length !== pageLength || annotationsWithTasks.length === 0,
        tasks: previousTasks.concat(tasks),
        lastLoadedPage: pageNumber,
      });
    } catch (error) {
      handleGenericError(error as Error);
    } finally {
      this.setState({
        isLoading: false,
      });
    }
  };

  toggleShowFinished = () => {
    this.setState(
      (prevState) => ({
        showFinishedTasks: !prevState.showFinishedTasks,
      }),
      () => {
        if (this.getCurrentModeState().lastLoadedPage === -1) this.fetchNextPage(0);
      },
    );
  };

  openTransferModal(annotationId: string) {
    this.setState({
      isTransferModalVisible: true,
      currentAnnotationId: annotationId,
    });
  }

  renderActions = (task: APITaskWithAnnotation) => {
    const { annotation } = task;
    const isAdmin =
      this.props.activeUser.isAdmin ||
      this.props.activeUser.teams
        .filter((team) => team.isTeamManager)
        .map((team) => team.name)
        .includes(task.team);
    const label = this.props.isAdminView ? (
      <span>
        <EyeOutlined />
        View
      </span>
    ) : (
      <span>
        <PlayCircleOutlined />
        Open
      </span>
    );
    return task.annotation.state === "Finished" ? (
      <div>
        <CheckCircleOutlined />
        Finished
        <br />
      </div>
    ) : (
      <div>
        <Link to={`/annotations/${annotation.id}`}>{label}</Link>
        <br />
        {isAdmin || this.props.isAdminView ? (
          <div>
            <LinkButton onClick={() => this.openTransferModal(annotation.id)}>
              <TeamOutlined />
              Transfer
            </LinkButton>
            <br />
          </div>
        ) : null}
        {isAdmin ? (
          <div>
            <AsyncLink
              href="#"
              onClick={() => {
                const isVolumeIncluded = getVolumeDescriptors(annotation).length > 0;
                return downloadAnnotation(annotation.id, "Task", isVolumeIncluded);
              }}
              icon={<DownloadOutlined />}
            >
              Download
            </AsyncLink>
            <br />
            <LinkButton onClick={() => this.resetTask(annotation)}>
              <RollbackOutlined />
              Reset
            </LinkButton>
            <br />
            <LinkButton onClick={() => this.cancelAnnotation(annotation)}>
              <DeleteOutlined />
              Reset and Cancel
            </LinkButton>
            <br />
          </div>
        ) : null}
        {this.props.isAdminView ? null : (
          <LinkButton onClick={() => this.confirmFinish(task)}>
            <CheckCircleOutlined />
            Finish
          </LinkButton>
        )}
      </div>
    );
  };

  resetTask(annotation: APIAnnotation) {
    Modal.confirm({
      content: messages["task.confirm_reset"],
      cancelText: messages.no,
      okText: messages.yes,
      onOk: async () => {
        await resetAnnotation(annotation.id, annotation.typ);
        Toast.success(messages["annotation.reset_success"]);
      },
    });
  }

  cancelAnnotation(annotation: APIAnnotation) {
    const annotationId = annotation.id;
    Modal.confirm({
      content: messages["annotation.delete"],
      cancelText: messages.no,
      okText: messages.yes,
      onOk: async () => {
        await deleteAnnotation(annotationId, annotation.typ);
        this.setCurrentModeState({
          tasks: this.getCurrentModeState().tasks.filter((t) => t.annotation.id !== annotationId),
        });
      },
    });
  }

  async confirmGetNewTask(): Promise<void> {
    if (this.state.unfinishedModeState.tasks.length === 0) {
      this.getNewTask();
    } else {
      let modalContent = messages["task.request_new"];
      const likelyNextTask = await peekNextTasks();

      if (likelyNextTask != null) {
        modalContent += `\n${messages["task.peek_next"]({
          projectName: likelyNextTask.projectName,
        })}`;
      }

      Modal.confirm({
        content: modalContent,
        onOk: () => this.getNewTask(),
      });
    }
  }

  async getNewTask() {
    this.setState({
      isLoading: true,
    });

    try {
      const newTaskAnnotation = await requestTask();
      this.setState((prevState) => ({
        unfinishedModeState: {
          ...prevState.unfinishedModeState,
          tasks: prevState.unfinishedModeState.tasks.concat([
            convertAnnotationToTaskWithAnnotationType(newTaskAnnotation),
          ]),
        },
      }));
    } catch (ex) {
      // catch exception so that promise does not fail and the modal will close
    } finally {
      this.setState({
        isLoading: false,
      });
    }
  }

  handleTransferredTask() {
    this.setState({
      isTransferModalVisible: false,
    });

    const removeTransferredTask = (
      tasks: APITaskWithAnnotation[],
      currentAnnotationId: string | null | undefined,
    ) => tasks.filter((t) => t.annotation.id !== currentAnnotationId);

    this.setCurrentModeState({
      tasks: removeTransferredTask(this.getCurrentTasks(), this.state.currentAnnotationId),
    });
  }

  getCurrentTasks() {
    return this.getCurrentModeState().tasks;
  }

  renderPlaceholder() {
    return this.state.isLoading ? null : (
      <>
        <p>
          You have no assigned tasks. Request a new task by clicking on the{" "}
          <strong>Get a New Task</strong> button.
        </p>
        {this.props.activeUser.isAdmin && (
          <>
            <p>
              Tasks are a powerful way to distribute annotation jobs among groups of users.{" "}
              <Link to="/tasks">Create new tasks from the admin menu</Link>.
            </p>
            <p>
              To learn more about the task system in webKnossos,{" "}
              <a
                href="https://docs.webknossos.org/webknossos/tasks.html"
                rel="noopener noreferrer"
                target="_blank"
              >
                check out the documentation
              </a>
              .
            </p>
          </>
        )}
      </>
    );
  }

  renderTaskList() {
    const tasks = this.getCurrentTasks().sort(
      Utils.compareBy(
        typeHint,
        (task) => (this.state.showFinishedTasks ? task.annotation.modified : task.created),
        false,
      ),
    );
    const descriptionClassName = classNames("task-type-description", {
      short: this.state.showFinishedTasks || this.props.isAdminView,
    });

    const TaskCardTitle = ({ task }: { task: APITaskWithAnnotation }) => (
      <React.Fragment>
        <span
          style={{
            marginRight: 8,
          }}
        >
          {task.projectName} (<FormattedDate timestamp={task.created} />)
        </span>
        {getSkeletonDescriptor(task.annotation) == null ? null : <Tag color="green">skeleton</Tag>}
        {getVolumeDescriptors(task.annotation).length === 0 ? null : (
          <Tag color="orange">volume</Tag>
        )}
        {task.type.settings.allowedModes.map((mode) => (
          <Tag key={mode}>{mode}</Tag>
        ))}
      </React.Fragment>
    );

    const TaskCard = (task: APITaskWithAnnotation) =>
      this.state.showFinishedTasks ? (
        <Card
          key={task.id}
          style={{
            margin: "10px",
          }}
        >
          <Row gutter={16}>
            <Col span={7}>
              <b>Task ID:</b> {task.id}
            </Col>
            <Col span={7}>
              <b>Project:</b> {task.projectName}
            </Col>
            <Col span={7}>
              <b>Finished:</b> <FormattedDate timestamp={task.annotation.modified} />
            </Col>
            <Col span={3}>{this.renderActions(task)}</Col>
          </Row>
        </Card>
      ) : (
        <Card
          key={task.id}
          title={<TaskCardTitle task={task} />}
          style={{
            margin: "10px",
          }}
        >
          <Row gutter={16}>
            <Col span={16}>
              <div className={descriptionClassName}>
                <Markdown
                  source={task.type.description}
                  options={{
                    html: false,
                    breaks: true,
                    linkify: true,
                  }}
                />
              </div>
            </Col>
            <Col span={8}>
              <p
                style={{
                  marginBottom: 14,
                }}
              >
                <b>Task ID:</b> {task.id}
                <br />
                <b>Task Type:</b> {task.type.summary}
              </p>
              {this.renderActions(task)}
            </Col>
          </Row>
        </Card>
      );

    return (
      <List
        dataSource={tasks}
        pagination={{
          defaultPageSize: 50,
        }}
        loading={this.state.isLoading}
        renderItem={TaskCard}
        locale={{
          emptyText: this.renderPlaceholder(),
        }}
      />
    );
  }

  render() {
    return (
      <div>
        <div className="pull-right">
          <AsyncButton
            type="primary"
            icon={<UserAddOutlined />}
            onClick={() => this.confirmGetNewTask()}
            disabled={this.props.isAdminView && this.props.userId != null}
          >
            Get a New Task
          </AsyncButton>
          <Button
            onClick={this.toggleShowFinished}
            style={{
              marginLeft: 20,
            }}
          >
            Show {this.getFinishVerb()} Tasks Only
          </Button>
        </div>
        <h3 id="tasksHeadline" className="TestTasksHeadline">
          {this.state.showFinishedTasks ? "My Finished Tasks" : null}
        </h3>
        <div
          className="clearfix"
          style={{
            margin: "20px 0px",
          }}
        />
        {this.renderTaskList()}
        <div
          style={{
            textAlign: "right",
          }}
        >
          {!this.getCurrentModeState().loadedAllTasks ? (
            <Link
              to="#"
              onClick={() => this.fetchNextPage(this.getCurrentModeState().lastLoadedPage + 1)}
            >
              Load more Tasks
            </Link>
          ) : null}
        </div>
        <TransferTaskModal
          visible={this.state.isTransferModalVisible}
          annotationId={this.state.currentAnnotationId}
          onCancel={() =>
            this.setState({
              isTransferModalVisible: false,
            })
          }
          onChange={() => this.handleTransferredTask()}
        />
      </div>
    );
  }
}

const mapStateToProps = (state: OxalisState): StateProps => ({
  activeUser: enforceActiveUser(state.activeUser),
});

const connector = connect(mapStateToProps);
export default connector(withRouter<RouteComponentProps & Props, any>(DashboardTaskListView));
