import { Col, Row } from "antd";
import type { RouteComponentProps } from "react-router-dom";
import { withRouter } from "react-router-dom";
import React from "react";
import * as Utils from "libs/utils";
import window from "libs/window";
import LoginForm from "./login_form";
type Props = {
  history: RouteComponentProps["history"];
  redirect?: string;
};

function LoginView({ history, redirect }: Props) {
  const onLoggedIn = () => {
    if (!Utils.hasUrlParam("redirectPage")) {
      if (redirect) {
        // Use "redirect" prop for internal redirects, e.g. for SecuredRoutes
        history.push(redirect);
      } else {
        history.push("/dashboard");
      }
    } else {
      // Use "redirectPage" URL parameter to cause a full page reload and redirecting to external sites
      // e.g. Discuss
      window.location.replace(Utils.getUrlParamValue("redirectPage"));
    }
  };

  return (
    <Row
      // @ts-expect-error ts-migrate(2322) FIXME: Type '{ children: Element; type: string; justify: ... Remove this comment to see the full error message
      type="flex"
      justify="center"
      style={{
        padding: 50,
      }}
      align="middle"
    >
      <Col xs={24} sm={16} md={8}>
        <h3>Login</h3>
        <LoginForm layout="horizontal" onLoggedIn={onLoggedIn} />
      </Col>
    </Row>
  );
}

export default withRouter<RouteComponentProps & Props, any>(LoginView);
