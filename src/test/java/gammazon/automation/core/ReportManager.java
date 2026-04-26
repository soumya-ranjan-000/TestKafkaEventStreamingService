package gammazon.automation.core;

import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.reporter.ExtentSparkReporter;


public class ReportManager {

    private static ExtentReports extent;
    private static ThreadLocal<ExtentTest> test = new ThreadLocal<>();

    public static ExtentReports getInstance() {
        if (extent == null) {
            ExtentSparkReporter spark = new ExtentSparkReporter("reports/TestReport.html");
            spark.config().setDocumentTitle("API Test Report");
            spark.config().setReportName("REST API Tests");

            extent = new ExtentReports();
            extent.attachReporter(spark);
        }
        return extent;
    }

    public static ExtentTest getTest() {
        return test.get();  // ← ThreadLocal, safe for parallel
    }

    public static void setTest(ExtentTest extentTest) {
        test.set(extentTest);
    }

    public static void flush() {
        extent.flush();  // writes report to file
    }

    public static void removeTest() {
        test.remove();
    }
}
