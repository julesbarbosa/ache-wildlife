package achecrawler.crawler.async;

import achecrawler.crawler.crawlercommons.fetcher.BaseFetchException;
import achecrawler.crawler.crawlercommons.fetcher.BaseFetcher;
import achecrawler.crawler.crawlercommons.fetcher.FetchedResult;
import achecrawler.crawler.crawlercommons.fetcher.Payload;
import achecrawler.crawler.crawlercommons.util.Headers;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.v131.network.Network;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class SeleniumFetcher extends BaseFetcher {

    private static final Logger logger = LoggerFactory.getLogger(SeleniumFetcher.class);

    private final ThreadLocal<ChromeDriver> driver;

    public SeleniumFetcher(int waitTimeoutSeconds,
                           int maxTotalBufferSize,
                           int maxResourceBufferSize,
                           int maxPostDataSize) {

        WebDriverManager.chromedriver()
                // .browserVersion("114.0.5735.90")
                .setup();

//        System.setProperty("webdriver.chrome.driver", "/path/to/chromedriver");

        driver = ThreadLocal.withInitial(() -> {

            // Setup Chrome options (for headless mode and other settings)
            ChromeDriver driver = createChromeDriver();
            driver.manage().timeouts().implicitlyWait(waitTimeoutSeconds, TimeUnit.SECONDS);

//            WebDriverWait wait = new WebDriverWait(this.driver, Duration.ofSeconds(30));
//            wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".content-class"))); // Adjust the selector

            // Enable Chrome DevTools to capture network traffic (for HTTP headers)
            DevTools devTools = driver.getDevTools();
            devTools.createSession();
            devTools.send(Network.enable(Optional.of(maxTotalBufferSize), Optional.of(maxResourceBufferSize), Optional.of(maxPostDataSize)));

            return driver;
        });
    }

    private static ChromeDriver createChromeDriver() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--disable-extensions");
        options.addArguments("--disable-gpu");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--headless");
        options.addArguments("--disable-software-rasterizer");
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--user-agent=Mozilla/5.0 (Windows Phone 10.0; Android 4.2.1; Microsoft; Lumia 640 XL LTE) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/42.0.2311.135 Mobile Safari/537.36 Edge/12.10166");
//            options.addArguments("--user-data-dir=/tmp/chrome-user-data");
//            options.setBinary("/usr/bin/google-chrome");

        // Initialize WebDriver with ChromeDriver
        return new ChromeDriver(options);
    }

    @Override
    public FetchedResult get(String url, Payload payload) throws BaseFetchException {
        URL urlObject;
        try {
            urlObject = new URL(url);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid URL provided: " + url);
        }

        logger.info("Current URL:{}", url);
        logger.info("Current URLobj:{}", urlObject);

        final AtomicInteger statusCode = new AtomicInteger(-1);
        final AtomicReference<String> reasonPhrase = new AtomicReference<>(null);
        Headers headers = new Headers();

        driver.get().getDevTools().addListener(Network.responseReceived(), ResponseReceived -> {
            if (ResponseReceived.getResponse().getUrl().equals(url)) {
                statusCode.set(ResponseReceived.getResponse().getStatus());
                reasonPhrase.set(ResponseReceived.getResponse().getStatusText());
                org.openqa.selenium.devtools.v131.network.model.Headers responseHeaderMap = ResponseReceived.getResponse().getHeaders();
                for (var header : responseHeaderMap.entrySet()) {
                    if (header.getValue() instanceof String) {
                        headers.add(header.getKey(), (String) header.getValue());
                    } else {
                        throw new IllegalArgumentException("TODO");
                    }
                }
            }
        });

        long startTime = System.currentTimeMillis();
        this.driver.get().get(urlObject.toString());
        String content = this.driver.get().getPageSource();
        String contentType = headers.get("Content-Type") != null ? headers.get("Content-Type") : "";

        long endTime = System.currentTimeMillis(); // Capture the end time after the page has
        long fetchTime = System.currentTimeMillis();
        String redirectedUrl = this.driver.get().getCurrentUrl();
        String newBaseUrl = null;

        URL redirectedUrlObject = null;
        if (redirectedUrl != null) {
            try {
                redirectedUrlObject = new URL(redirectedUrl);
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("Invalid URL provided: " + url);
            }
        }
        long responseTime = endTime - startTime;
        int responseRate = (int) responseTime;

        String hostAddress = redirectedUrlObject != null ? redirectedUrlObject.getHost() : null;
        logger.info("HostAddress: {}", hostAddress);

        logger.info("Redirected URL: {}", redirectedUrl);

        int numRedirects = 0;
        if (!urlObject.toString().equals(redirectedUrl)) {
            numRedirects = 1;
            newBaseUrl = redirectedUrl;
        }

        String reasonPhraseString = reasonPhrase.get();

        // clean-up resources before fetch is done
        driver.get().getDevTools().clearListeners();

        return new FetchedResult(url, redirectedUrl, fetchTime, headers, content.getBytes(),
                contentType, responseRate, payload, newBaseUrl, numRedirects, hostAddress, statusCode.get(), reasonPhraseString);

    }

    @Override
    public void abort() {
        if (this.driver.get() != null) {
            this.driver.get().quit();
        }
    }
}
