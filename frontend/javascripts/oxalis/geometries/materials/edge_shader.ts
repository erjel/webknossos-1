import * as THREE from "three";
import { COLOR_TEXTURE_WIDTH_FIXED } from "oxalis/geometries/materials/node_shader";
import type { Uniforms } from "oxalis/geometries/materials/plane_material_factory";

class EdgeShader {
  material: THREE.RawShaderMaterial;
  // @ts-expect-error ts-migrate(2564) FIXME: Property 'uniforms' has no initializer and is not ... Remove this comment to see the full error message
  uniforms: Uniforms;

  constructor(treeColorTexture: THREE.DataTexture) {
    this.setupUniforms(treeColorTexture);
    this.material = new THREE.RawShaderMaterial({
      // @ts-expect-error ts-migrate(2565) FIXME: Property 'uniforms' is used before being assigned.
      uniforms: this.uniforms,
      vertexShader: this.getVertexShader(),
      fragmentShader: this.getFragmentShader(),
    });
  }

  setupUniforms(treeColorTexture: THREE.DataTexture): void {
    this.uniforms = {
      activeTreeId: {
        value: NaN,
      },
      treeColors: {
        value: treeColorTexture,
      },
    };
  }

  getMaterial(): THREE.RawShaderMaterial {
    return this.material;
  }

  getVertexShader(): string {
    return `
precision highp float;
precision highp int;

varying vec3 color;

uniform mat4 modelViewMatrix;
uniform mat4 projectionMatrix;
uniform float activeTreeId;

uniform sampler2D treeColors;

attribute vec3 position;
attribute float treeId;

void main() {
    vec2 treeIdToTextureCoordinate = vec2(fract(
      treeId / ${COLOR_TEXTURE_WIDTH_FIXED}),
      treeId / (${COLOR_TEXTURE_WIDTH_FIXED} * ${COLOR_TEXTURE_WIDTH_FIXED}
    ));
    bool isVisible = texture2D(treeColors, treeIdToTextureCoordinate).a == 1.0;

    if (!isVisible) {
      gl_Position = vec4(-1.0, -1.0, -1.0, -1.0);
      return;
    }

    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
    color = texture2D(treeColors, treeIdToTextureCoordinate).rgb;
}`;
  }

  getFragmentShader(): string {
    return `
precision highp float;

varying vec3 color;

void main()
{
    gl_FragColor = vec4(color, 1.0);
}`;
  }
}

export default EdgeShader;
