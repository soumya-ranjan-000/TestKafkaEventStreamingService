package gammazon.automation.core;

import com.aventstack.extentreports.ExtentTest;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.filter.Filter;
import io.restassured.filter.log.LogDetail;
import io.restassured.http.ContentType;
import io.restassured.module.jsv.JsonSchemaValidator;
import io.restassured.response.Response;
import io.restassured.specification.*;
import lombok.Getter;
import org.hamcrest.Matchers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;

public class BaseAPI {

    // ─── Logger ──────────────────────────────────────────────────
    private static final Logger log = LoggerFactory.getLogger(BaseAPI.class);

    private RequestSpecification requestSpecification;
    private ResponseSpecification responseSpecification;
    private Map<String, String> headers;
    private Map<String, String> params;
    private String baseUrl;
    @Getter
    private Response response;

    // ─── Constructors ────────────────────────────────────────────

    public BaseAPI() {}

    public BaseAPI(String baseUrl) {
        this(baseUrl, null, null);
    }

    public BaseAPI(String baseUrl, Map<String, String> headers) {
        this(baseUrl, headers, null);
    }

    public BaseAPI(String baseUrl, Map<String, String> headers, Map<String, String> params) {
        this.baseUrl = baseUrl;
        this.headers = headers;
        this.params = params;
        log.info("BaseAPI initialized with baseUrl: {}", baseUrl);
    }

    // ─── Extent Report Filter ────────────────────────────────────

    private Filter extentReportFilter() {
        return (requestSpec, responseSpec, ctx) -> {

            Response res = ctx.next(requestSpec, responseSpec);
            ExtentTest test = ReportManager.getTest();

            if (test != null) {

                // ─── Request Table ───────────────────────────────
                test.info(
                        "<table style='width:100%; border-collapse:collapse; font-size:13px;'>"
                                + "<tr style='background:#2d2d2d; color:white;'>"
                                + "  <th style='padding:8px; text-align:left;'>Field</th>"
                                + "  <th style='padding:8px; text-align:left;'>Value</th>"
                                + "</tr>"
                                + tableRow("➡ Method",  requestSpec.getMethod())
                                + tableRow("➡ URI",     requestSpec.getURI())
                                + tableRow("➡ Headers", requestSpec.getHeaders().toString())
                                + tableRow("➡ Body",    requestSpec.getBody() != null
                                ? requestSpec.getBody().toString() : "N/A")
                                + "</table>"
                );

                // ─── Response Status Row ─────────────────────────
                String statusColor = res.statusCode() < 300 ? "#2ecc71" : "#e74c3c";
                test.info(
                        "<table style='width:100%; border-collapse:collapse; font-size:13px;'>"
                                + "<tr style='background:#2d2d2d; color:white;'>"
                                + "  <th style='padding:8px;'>Field</th>"
                                + "  <th style='padding:8px;'>Value</th>"
                                + "</tr>"
                                + tableRow("⬅ Status",
                                "<span style='color:" + statusColor + "; font-weight:bold;'>"
                                        + res.statusCode() + "</span>")
                                + tableRow("⬅ Time",  res.time() + " ms")
                                + tableRow("⬅ Content-Type", res.contentType())
                                + "</table>"
                );

                // ─── Formatted Response Body ─────────────────────
                test.info(formatResponseBody(res)); // ← formatted body
            }

            return res;
        };
    }

    // ─── Table Row Helper ─────────────────────────────────────────
    private String tableRow(String key, String value) {
        return "<tr style='border-bottom:1px solid #444;'>"
                + "<td style='padding:8px; font-weight:bold; color:#569cd6; width:150px;'>"
                + key + "</td>"
                + "<td style='padding:8px; color:#333;'>"
                + (value != null ? value : "N/A") + "</td>"
                + "</tr>";
    }

    // ─── Request Specification ───────────────────────────────────

    public RequestSpecification getRequestSpecification() {
        if (requestSpecification == null) {
            log.debug("Building RequestSpecification for baseUrl: {}", baseUrl);

            RequestSpecBuilder builder = new RequestSpecBuilder()
                    .setBaseUri(this.baseUrl)
                    .setContentType(ContentType.JSON)
                    .log(LogDetail.ALL)               // ← console log
                    .addFilter(extentReportFilter()); // ← extent report log

            if (this.headers != null && !this.headers.isEmpty()) {
                builder.addHeaders(this.headers);
                log.debug("Headers added: {}", this.headers);
            }

            if (this.params != null && !this.params.isEmpty()) {
                builder.addQueryParams(this.params);
                log.debug("Query params added: {}", this.params);
            }

            this.requestSpecification = builder.build();
        }
        return requestSpecification;
    }

    // ─── Response Specification ──────────────────────────────────

    public ResponseSpecification getResponseSpecification() {
        if (responseSpecification == null) {
            this.responseSpecification = new ResponseSpecBuilder()
                    .expectContentType(ContentType.JSON)
                    .expectResponseTime(Matchers.lessThan(60000L))
                    .build();
        }
        return responseSpecification;
    }

    // ─── CRUD Methods ────────────────────────────────────────────

    public Response get(String endpoint) {
        log.info("GET → {}{}", baseUrl, endpoint);
        logToReport("GET", endpoint);
        try {
            this.response = RestAssured
                    .given().spec(getRequestSpecification())
                    .when().get(endpoint)
                    .then().spec(getResponseSpecification())
                    .extract().response();
            logSuccess("GET", endpoint, response);
        } catch (Exception e) {
            logFailure("GET", endpoint, e);
            throw e;
        }
        return this.response;
    }

    public Response post(String endpoint, Object body) {
        log.info("POST → {}{}", baseUrl, endpoint);
        logToReport("POST", endpoint);
        try {
            this.response = RestAssured
                    .given().spec(getRequestSpecification())
                    .body(body)
                    .when().post(endpoint)
                    .then().spec(getResponseSpecification())
                    .extract().response();
            logSuccess("POST", endpoint, response);
        } catch (Exception e) {
            logFailure("POST", endpoint, e);
            throw e;
        }
        return this.response;
    }

    public Response put(String endpoint, Object body) {
        log.info("PUT → {}{}", baseUrl, endpoint);
        logToReport("PUT", endpoint);
        try {
            this.response = RestAssured
                    .given().spec(getRequestSpecification())
                    .body(body)
                    .when().put(endpoint)
                    .then().spec(getResponseSpecification())
                    .extract().response();
            logSuccess("PUT", endpoint, response);
        } catch (Exception e) {
            logFailure("PUT", endpoint, e);
            throw e;
        }
        return this.response;
    }

    public Response patch(String endpoint, Object body) {
        log.info("PATCH → {}{}", baseUrl, endpoint);
        logToReport("PATCH", endpoint);
        try {
            this.response = RestAssured
                    .given().spec(getRequestSpecification())
                    .body(body)
                    .when().patch(endpoint)
                    .then().spec(getResponseSpecification())
                    .extract().response();
            logSuccess("PATCH", endpoint, response);
        } catch (Exception e) {
            logFailure("PATCH", endpoint, e);
            throw e;
        }
        return this.response;
    }

    public Response delete(String endpoint) {
        log.info("DELETE → {}{}", baseUrl, endpoint);
        logToReport("DELETE", endpoint);
        try {
            this.response = RestAssured
                    .given().spec(getRequestSpecification())
                    .when().delete(endpoint)
                    .then().spec(getResponseSpecification())
                    .extract().response();
            logSuccess("DELETE", endpoint, response);
        } catch (Exception e) {
            logFailure("DELETE", endpoint, e);
            throw e;
        }
        return this.response;
    }

    // ─── Private Logging Helpers ─────────────────────────────────

    private void logToReport(String method, String endpoint) {
        ExtentTest test = ReportManager.getTest();
        if (test != null) {
            test.info("<b>🚀 " + method + "</b> → " + baseUrl + endpoint);
        }
    }

    private static void logInfoToReport(String message) {
        ExtentTest test = ReportManager.getTest();
        if (test != null) {
            test.info("<b> "+message+" </b>");
        }
    }

    private static void logErrorToReport(String message) {
        ExtentTest test = ReportManager.getTest();
        if (test != null) {
            test.info("<b>❌ "+message+" </b>");
        }
    }

    private void logSuccess(String method, String endpoint, Response res) {
        log.info("{} {} → {} in {}ms", method, endpoint, res.statusCode(), res.time());
        ExtentTest test = ReportManager.getTest();
        if (test != null) {
            test.pass("<b>✅ " + method + " " + endpoint
                    + "</b> → Status: " + res.statusCode()
                    + " | Time: " + res.time() + "ms");
        }
    }

    private void logFailure(String method, String endpoint, Exception e) {
        log.error("{} {} → FAILED: {}", method, endpoint, e.getMessage());
        ExtentTest test = ReportManager.getTest();
        if (test != null) {
            test.fail("<b>❌ " + method + " " + endpoint + " FAILED</b><br>" + e.getMessage());
        }
    }

    // ─── Get Last Response ───────────────────────────────────────

    private String formatResponseBody(Response res) {
        String contentType = res.contentType();

        // pick color based on status code
        boolean isSuccess = res.statusCode() < 300;
        String borderColor = isSuccess ? "#28a745" : "#dc3545"; // green or red
        String bgColor     = isSuccess ? "#f8f9fa" : "#fff5f5"; // light or light red
        String textColor   = "#212529"; // always dark text

        if (contentType != null && contentType.contains("application/json")) {
            return "<details open>"
                    + "<summary><b>⬅ Response Body (JSON) — "
                    + "<span style='color:" + borderColor + ";'>"
                    + res.statusCode() + "</span></b></summary>"
                    + "<pre style='"
                    + "background:" + bgColor + ";"
                    + "color:" + textColor + ";"
                    + "padding:12px;"
                    + "border-radius:6px;"
                    + "border-left:5px solid " + borderColor + ";"
                    + "font-size:12px;"
                    + "overflow:auto;"
                    + "max-height:400px;"
                    + "white-space:pre-wrap;"
                    + "'>"
                    + escapeHtml(res.asPrettyString())
                    + "</pre>"
                    + "</details>";

        } else if (contentType != null && contentType.contains("application/xml")) {
            return "<details open>"
                    + "<summary><b>⬅ Response Body (XML)</b></summary>"
                    + "<pre style='"
                    + "background:#fffbf0;"
                    + "color:#212529;"
                    + "border-left:5px solid #ffc107;"
                    + "padding:12px; border-radius:6px;"
                    + "font-size:12px; overflow:auto; max-height:400px;'>"
                    + escapeHtml(res.asPrettyString())
                    + "</pre>"
                    + "</details>";

        } else {
            return "<details open>"
                    + "<summary><b>⬅ Response Body (Text)</b></summary>"
                    + "<pre style='background:#f8f9fa; color:#212529;"
                    + "padding:10px; border-radius:5px; font-size:12px;'>"
                    + escapeHtml(res.body().asString())
                    + "</pre>"
                    + "</details>";
        }
    }

    // ← prevents HTML injection from response body
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public static void validateSchema(String schemaPath, Response response) {
        try{
            assertThat(response.body().asString(),
                    JsonSchemaValidator.matchesJsonSchemaInClasspath(schemaPath));
            logInfoToReport("📃 Schema Validation ["+schemaPath+"] - Passed !");
        }catch(Exception e){
            logErrorToReport("📃 Schema Validation ["+schemaPath+"] - Failed !");
        }
    }
}