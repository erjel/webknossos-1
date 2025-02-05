/* eslint no-await-in-loop: 0 */
import urljoin from "url-join";
import type { PartialDatasetConfiguration } from "oxalis/store";
import type { Page } from "puppeteer";
// @ts-expect-error ts-migrate(7016) FIXME: Could not find a declaration file for module 'merg... Remove this comment to see the full error message
import mergeImg from "merge-img";
// @ts-expect-error ts-migrate(7016) FIXME: Could not find a declaration file for module 'pixe... Remove this comment to see the full error message
import pixelmatch from "pixelmatch";
import { RequestOptions } from "libs/request";
import { bufferToPng, isPixelEquivalent } from "./screenshot_helpers";
import type { APIDatasetId } from "../../types/api_flow_types";
import { createExplorational, updateDatasetConfiguration } from "../../admin/admin_rest_api";

export const { WK_AUTH_TOKEN } = process.env;

type Screenshot = {
  screenshot: Buffer;
  width: number;
  height: number;
};

function getDefaultRequestOptions(baseUrl: string): RequestOptions {
  if (!WK_AUTH_TOKEN) {
    throw new Error("No WK_AUTH_TOKEN specified.");
  }
  return {
    host: baseUrl,
    doNotInvestigate: true,
    headers: {
      "X-Auth-Token": WK_AUTH_TOKEN,
    },
  };
}

export async function screenshotDataset(
  page: Page,
  baseUrl: string,
  datasetId: APIDatasetId,
  optionalViewOverride?: string | null | undefined,
  optionalDatasetConfigOverride?: PartialDatasetConfiguration | null | undefined,
): Promise<Screenshot> {
  return _screenshotAnnotationHelper(
    page,
    baseUrl,
    datasetId,
    "skeleton",
    null,
    optionalViewOverride,
    optionalDatasetConfigOverride,
  );
}

export async function screenshotAnnotation(
  page: Page,
  baseUrl: string,
  datasetId: APIDatasetId,
  fallbackLayerName: string | null,
  optionalViewOverride?: string | null | undefined,
  optionalDatasetConfigOverride?: PartialDatasetConfiguration | null | undefined,
): Promise<Screenshot> {
  return _screenshotAnnotationHelper(
    page,
    baseUrl,
    datasetId,
    "hybrid",
    fallbackLayerName,
    optionalViewOverride,
    optionalDatasetConfigOverride,
  );
}

async function _screenshotAnnotationHelper(
  page: Page,
  baseUrl: string,
  datasetId: APIDatasetId,
  typ: "skeleton" | "volume" | "hybrid",
  fallbackLayerName: string | null,
  optionalViewOverride?: string | null | undefined,
  optionalDatasetConfigOverride?: PartialDatasetConfiguration | null | undefined,
): Promise<Screenshot> {
  const options = getDefaultRequestOptions(baseUrl);
  const createdExplorational = await createExplorational(
    datasetId,
    typ,
    fallbackLayerName,
    null,
    options,
  );

  if (optionalDatasetConfigOverride != null) {
    await updateDatasetConfiguration(datasetId, optionalDatasetConfigOverride, options);
  }

  await openTracingView(page, baseUrl, createdExplorational.id, optionalViewOverride);
  return screenshotTracingView(page);
}

export async function screenshotDatasetWithMapping(
  page: Page,
  baseUrl: string,
  datasetId: APIDatasetId,
  mappingName: string,
): Promise<Screenshot> {
  const options = getDefaultRequestOptions(baseUrl);
  const createdExplorational = await createExplorational(
    datasetId,
    "skeleton",
    null,
    null,
    options,
  );
  await openTracingView(page, baseUrl, createdExplorational.id);
  await page.evaluate(
    `webknossos.apiReady().then(async api => api.data.activateMapping("${mappingName}"))`,
  );
  await waitForMappingEnabled(page);
  return screenshotTracingView(page);
}
export async function screenshotDatasetWithMappingLink(
  page: Page,
  baseUrl: string,
  datasetId: APIDatasetId,
  optionalViewOverride: string | null | undefined,
): Promise<Screenshot> {
  const options = getDefaultRequestOptions(baseUrl);
  const createdExplorational = await createExplorational(
    datasetId,
    "skeleton",
    null,
    null,
    options,
  );
  await openTracingView(page, baseUrl, createdExplorational.id, optionalViewOverride);
  await waitForMappingEnabled(page);
  return screenshotTracingView(page);
}
export async function screenshotSandboxWithMappingLink(
  page: Page,
  baseUrl: string,
  datasetId: APIDatasetId,
  optionalViewOverride: string | null | undefined,
): Promise<Screenshot> {
  await openSandboxView(page, baseUrl, datasetId, optionalViewOverride);
  await waitForMappingEnabled(page);
  return screenshotTracingView(page);
}

async function waitForMappingEnabled(page: Page) {
  console.log("Waiting for mapping to be enabled");
  let isMappingEnabled;

  while (!isMappingEnabled) {
    await page.waitForTimeout(5000);
    isMappingEnabled = await page.evaluate(
      "webknossos.apiReady().then(async api => api.data.isMappingEnabled())",
    );
  }

  console.log("Mapping was enabled");
}

async function waitForTracingViewLoad(page: Page) {
  let inputCatchers;

  // @ts-expect-error ts-migrate(2339) FIXME: Property 'length' does not exist on type 'ElementH... Remove this comment to see the full error message
  while (inputCatchers == null || inputCatchers.length < 4) {
    await page.waitForTimeout(500);
    inputCatchers = await page.$(".inputcatcher");
  }
}

async function waitForRenderingFinish(page: Page) {
  const width = 1920;
  const height = 1080;
  let currentShot;
  let lastShot = await page.screenshot({
    fullPage: true,
  });
  let changedPixels = Infinity;

  // If the screenshot of the page didn't change in the last x seconds, rendering should be finished
  while (currentShot == null || !isPixelEquivalent(changedPixels, width, height)) {
    console.log(`Waiting for rendering to finish. Changed pixels: ${changedPixels}`);
    await page.waitForTimeout(10000);
    currentShot = await page.screenshot({
      fullPage: true,
    });

    if (lastShot != null) {
      changedPixels = pixelmatch(
        // The buffers need to be converted to png before comparing them
        // as they might have different lengths, otherwise (probably due to different png encodings)
        // @ts-expect-error ts-migrate(2345) FIXME: Argument of type 'string | Buffer' is not assignab... Remove this comment to see the full error message
        (await bufferToPng(lastShot, width, height)).data,
        // @ts-expect-error ts-migrate(2345) FIXME: Argument of type 'string | Buffer' is not assignab... Remove this comment to see the full error message
        (await bufferToPng(currentShot, width, height)).data,
        null,
        width,
        height,
        {
          threshold: 0.0,
        },
      );
    }

    lastShot = currentShot;
  }
}

async function openTracingView(
  page: Page,
  baseUrl: string,
  annotationId: string,
  optionalViewOverride?: string | null | undefined,
) {
  const urlSlug = optionalViewOverride != null ? `#${optionalViewOverride}` : "";
  const url = urljoin(baseUrl, `/annotations/${annotationId}${urlSlug}`);
  console.log(`Opening annotation view at ${url}`);
  await page.goto(url, {
    timeout: 0,
  });
  await waitForTracingViewLoad(page);
  console.log("Loaded annotation view");
  await waitForRenderingFinish(page);
  console.log("Finished rendering annotation view");
}

async function openSandboxView(
  page: Page,
  baseUrl: string,
  datasetId: APIDatasetId,
  optionalViewOverride: string | null | undefined,
) {
  const urlSlug = optionalViewOverride != null ? `#${optionalViewOverride}` : "";
  const url = urljoin(
    baseUrl,
    `/datasets/${datasetId.owningOrganization}/${datasetId.name}/sandbox/skeleton${urlSlug}`,
  );
  console.log(`Opening sandbox annotation view at ${url}`);
  await page.goto(url, {
    timeout: 0,
  });
  await waitForTracingViewLoad(page);
  console.log("Loaded annotation view");
  await waitForRenderingFinish(page);
  console.log("Finished rendering annotation view");
}

async function screenshotTracingView(page: Page): Promise<Screenshot> {
  console.log("Screenshot annotation view");
  // Take screenshots of the other rendered planes
  const PLANE_IDS = [
    "#inputcatcher_TDView",
    "#inputcatcher_PLANE_XY",
    "#inputcatcher_PLANE_YZ",
    "#inputcatcher_PLANE_XZ",
  ];
  const screenshots = [];

  for (const planeId of PLANE_IDS) {
    const element = await page.$(planeId);
    if (element == null)
      throw new Error(`Element ${planeId} not present, although page is loaded.`);
    const screenshot = await element.screenshot();
    screenshots.push(screenshot);
  }

  // Concatenate all screenshots
  const img = await mergeImg(screenshots);
  return new Promise((resolve) => {
    // @ts-expect-error ts-migrate(7006) FIXME: Parameter '_' implicitly has an 'any' type.
    img.getBuffer("image/png", (_, buffer) =>
      resolve({
        screenshot: buffer,
        width: img.bitmap.width,
        height: img.bitmap.height,
      }),
    );
  });
}

export default {};
