/* eslint import/no-extraneous-dependencies: ["error", {"peerDependencies": true}] */
import Backbone from "backbone";
import mockRequire from "mock-require";
import sinon from "sinon";
import _ from "lodash";
import { ControlModeEnum } from "oxalis/constants";
import SKELETONTRACING_OBJECT from "../fixtures/skeletontracing_object";
import VOLUMETRACING_OBJECT from "../fixtures/volumetracing_object";

const Request = {
  receiveJSON: sinon.stub(),
  sendJSONReceiveJSON: sinon.stub(),
  sendArraybufferReceiveArraybuffer: sinon.stub(),
  always: () => Promise.resolve(),
};
const ErrorHandling = {
  assertExtendContext: _.noop,
  assertExists: _.noop,
  assert: _.noop,
};
const window = {
  location: {
    pathname: "annotationUrl",
  },
  alert: console.log.bind(console),
  open: sinon.spy(),
};
const currentUser = {
  firstName: "SCM",
  lastName: "Boy",
};
const app = {
  vent: Backbone.Radio.channel("global"),
  currentUser,
};
export const KeyboardJS = {
  bind: _.noop,
  unbind: _.noop,
};
mockRequire("libs/keyboard", KeyboardJS);
mockRequire("libs/toast", { error: _.noop });
mockRequire("libs/window", window);
mockRequire("libs/request", Request);
mockRequire("libs/error_handling", ErrorHandling);
mockRequire("app", app);
mockRequire("oxalis/model/volumetracing/volumetracing", _.noop);

// Avoid node caching and make sure all mockRequires are applied
const UrlManager = mockRequire.reRequire("oxalis/controller/url_manager").default;
const Model = mockRequire.reRequire("oxalis/model").OxalisModel;
const OxalisApi = mockRequire.reRequire("oxalis/api/api_loader").default;

const modelData = {
  skeleton: SKELETONTRACING_OBJECT,
  volume: VOLUMETRACING_OBJECT,
};

export function setupOxalis(t, mode) {
  UrlManager.initialState = { position: [1, 2, 3] };
  const model = new Model();
  t.context.model = model;

  const webknossos = new OxalisApi(model);

  Request.receiveJSON.returns(Promise.resolve(_.cloneDeep(modelData[mode])));
  Request.receiveJSON
    .withArgs("/dataToken/generate?dataSetName=2012-09-28_ex145_07x2&dataLayerName=64007765-cef9-4e31-b206-dba795b5be17")
    .returns(Promise.resolve({ token: "secure-token" }));

  return model
    .fetch("tracingTypeValue", "tracingIdValue", ControlModeEnum.TRACE, true)
    .then(() => {
      // Trigger the event ourselves, as the OxalisController is not instantiated
      app.vent.trigger("webknossos:ready");
      webknossos.apiReady(2).then(apiObject => {
        t.context.api = apiObject;
      });
    })
    .catch(error => {
      console.error("model.fetch() failed", error);
      t.fail(error.message);
    });
}
