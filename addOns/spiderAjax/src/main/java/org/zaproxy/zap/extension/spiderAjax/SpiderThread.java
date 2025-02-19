/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2012 The ZAP Development Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zaproxy.zap.extension.spiderAjax;

import com.crawljax.browser.EmbeddedBrowser;
import com.crawljax.browser.WebDriverBackedEmbeddedBrowser;
import com.crawljax.core.CrawljaxRunner;
import com.crawljax.core.configuration.BrowserConfiguration;
import com.crawljax.core.configuration.CrawljaxConfiguration;
import com.crawljax.core.configuration.CrawljaxConfiguration.CrawljaxConfigurationBuilder;
import com.crawljax.core.configuration.ProxyConfiguration;
import com.crawljax.core.plugin.OnBrowserCreatedPlugin;
import com.crawljax.core.plugin.Plugins;
import com.google.common.collect.ImmutableSortedSet;
import com.google.inject.ProvisionException;
import java.awt.EventQueue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Provider;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.URIException;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.control.Control;
import org.parosproxy.paros.model.HistoryReference;
import org.parosproxy.paros.model.Session;
import org.parosproxy.paros.network.HttpHeader;
import org.parosproxy.paros.network.HttpMalformedHeaderException;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpResponseHeader;
import org.parosproxy.paros.network.HttpSender;
import org.parosproxy.paros.view.View;
import org.zaproxy.addon.network.ExtensionNetwork;
import org.zaproxy.addon.network.server.HttpMessageHandler;
import org.zaproxy.addon.network.server.HttpMessageHandlerContext;
import org.zaproxy.addon.network.server.Server;
import org.zaproxy.zap.extension.selenium.ExtensionSelenium;
import org.zaproxy.zap.extension.spiderAjax.SpiderListener.ResourceState;
import org.zaproxy.zap.model.ScanEventPublisher;
import org.zaproxy.zap.network.HttpResponseBody;
import org.zaproxy.zap.users.User;

public class SpiderThread implements Runnable {

    private static final String LOCAL_PROXY_IP = "127.0.0.1";

    private final String displayName;
    private final AjaxSpiderTarget target;
    private final HttpPrefixUriValidator httpPrefixUriValidator;
    private CrawljaxRunner crawljax;
    private boolean running;
    private final Session session;
    private static final Logger LOGGER = LogManager.getLogger(SpiderThread.class);

    private HttpResponseHeader outOfScopeResponseHeader;
    private HttpResponseBody outOfScopeResponseBody;
    private List<SpiderListener> spiderListeners;
    private final List<String> exclusionList;
    private final String targetHost;
    private Server proxy;
    private int proxyPort;
    private final ExtensionAjax extension;
    private AuthenticationHandler authHandler;

    /**
     * Constructs a {@code SpiderThread} for the given target.
     *
     * @param displayName the name of the scan, must not be {@code null}.
     * @param target the target, must not be {@code null}.
     * @param extension the extension, must not be {@code null}.
     * @param spiderListener the listener, must not be {@code null}.
     */
    SpiderThread(
            String displayName,
            AjaxSpiderTarget target,
            ExtensionAjax extension,
            SpiderListener spiderListener,
            ExtensionNetwork extensionNetwork) {
        this.displayName = displayName;
        this.target = target;
        HttpPrefixUriValidator validator = null;
        try {
            validator =
                    target.isSubtreeOnly()
                            ? new HttpPrefixUriValidator(
                                    new URI(target.getStartUri().toASCIIString(), true))
                            : null;
        } catch (URIException e) {
            LOGGER.error("Failed to create subtree validator:", e);
        }
        this.httpPrefixUriValidator = validator;
        this.running = false;
        spiderListeners = new ArrayList<>(2);
        spiderListeners.add(spiderListener);
        this.session = extension.getModel().getSession();
        this.exclusionList = new ArrayList<>();
        exclusionList.addAll(session.getExcludeFromSpiderRegexs());
        exclusionList.addAll(session.getGlobalExcludeURLRegexs());
        this.targetHost = target.getStartUri().getHost();
        this.extension = extension;

        createOutOfScopeResponse(
                extension.getMessages().getString("spiderajax.outofscope.response"));

        proxy =
                extensionNetwork.createHttpProxy(
                        HttpSender.AJAX_SPIDER_INITIATOR, new SpiderProxyListener());
    }

    private void createOutOfScopeResponse(String response) {
        outOfScopeResponseBody = new HttpResponseBody();
        outOfScopeResponseBody.setBody(response.getBytes(StandardCharsets.UTF_8));

        final StringBuilder strBuilder = new StringBuilder(150);
        final String crlf = HttpHeader.CRLF;
        strBuilder.append("HTTP/1.1 403 Forbidden").append(crlf);
        strBuilder.append(HttpHeader.PRAGMA).append(": ").append("no-cache").append(crlf);
        strBuilder.append(HttpHeader.CACHE_CONTROL).append(": ").append("no-cache").append(crlf);
        strBuilder
                .append(HttpHeader.CONTENT_TYPE)
                .append(": ")
                .append("text/plain; charset=UTF-8")
                .append(crlf);
        strBuilder
                .append(HttpHeader.CONTENT_LENGTH)
                .append(": ")
                .append(outOfScopeResponseBody.length())
                .append(crlf);

        HttpResponseHeader responseHeader;
        try {
            responseHeader = new HttpResponseHeader(strBuilder.toString());
        } catch (HttpMalformedHeaderException e) {
            LOGGER.error("Failed to create a valid! response header: ", e);
            responseHeader = new HttpResponseHeader();
        }
        outOfScopeResponseHeader = responseHeader;
    }

    /**
     * @return the SpiderThread object
     */
    public SpiderThread getSpiderThread() {
        return this;
    }

    /**
     * @return the SpiderThread object
     */
    public boolean isRunning() {
        return this.running;
    }

    public CrawljaxConfiguration createCrawljaxConfiguration() {
        CrawljaxConfigurationBuilder configurationBuilder =
                CrawljaxConfiguration.builderFor(target.getStartUri().toString());

        // For Crawljax assume everything in scope, SpiderProxyListener does the actual scope
        // checks.
        configurationBuilder.setCrawlScope(url -> true);

        configurationBuilder.setProxyConfig(
                ProxyConfiguration.manualProxyOn(LOCAL_PROXY_IP, proxyPort));

        configurationBuilder.setBrowserConfig(
                new BrowserConfiguration(
                        com.crawljax.browser.EmbeddedBrowser.BrowserType.FIREFOX,
                        target.getOptions().getNumberOfBrowsers(),
                        new AjaxSpiderBrowserBuilder(target.getOptions().getBrowserId())));

        if (target.getOptions().isClickDefaultElems()) {
            configurationBuilder.crawlRules().clickDefaultElements();
        } else {
            for (String elem : target.getOptions().getElemsNames()) {
                configurationBuilder.crawlRules().click(elem);
            }
        }

        for (var excludedElement : target.getExcludedElements()) {
            var crawlElement =
                    configurationBuilder.crawlRules().dontClick(excludedElement.getElement());
            if (StringUtils.isNotBlank(excludedElement.getXpath())) {
                crawlElement.underXPath(excludedElement.getXpath());
            }
            if (StringUtils.isNotBlank(excludedElement.getText())) {
                crawlElement.withText(excludedElement.getText());
            }
            if (StringUtils.isNotBlank(excludedElement.getAttributeName())
                    && StringUtils.isNotBlank(excludedElement.getAttributeValue())) {
                crawlElement.withAttribute(
                        excludedElement.getAttributeName(), excludedElement.getAttributeValue());
            }
        }

        configurationBuilder.crawlRules().followExternalLinks(true);
        configurationBuilder
                .crawlRules()
                .insertRandomDataInInputForms(target.getOptions().isRandomInputs());
        configurationBuilder
                .crawlRules()
                .waitAfterEvent(target.getOptions().getEventWait(), TimeUnit.MILLISECONDS);
        configurationBuilder
                .crawlRules()
                .waitAfterReloadUrl(target.getOptions().getReloadWait(), TimeUnit.MILLISECONDS);

        if (target.getOptions().getMaxCrawlStates() == 0) {
            configurationBuilder.setUnlimitedStates();
        } else {
            configurationBuilder.setMaximumStates(target.getOptions().getMaxCrawlStates());
        }

        configurationBuilder.setMaximumDepth(target.getOptions().getMaxCrawlDepth());
        configurationBuilder.setMaximumRunTime(
                target.getOptions().getMaxDuration(), TimeUnit.MINUTES);
        configurationBuilder.crawlRules().clickOnce(target.getOptions().isClickElemsOnce());

        configurationBuilder.addPlugin(DummyPlugin.DUMMY_PLUGIN);

        return configurationBuilder.build();
    }

    /** Instantiates the crawljax classes. */
    @Override
    public void run() {
        LOGGER.info(
                "Running Crawljax (with {}): {}", target.getOptions().getBrowserId(), displayName);
        this.running = true;
        notifyListenersSpiderStarted();
        SpiderEventPublisher.publishScanEvent(
                ScanEventPublisher.SCAN_STARTED_EVENT,
                0,
                this.target.toTarget(),
                this.target.getUser());

        User user = target.getUser();
        if (user != null) {
            for (AuthenticationHandler ah : extension.getAuthenticationHandlers()) {
                if (ah.enableAuthentication(user)) {
                    authHandler = ah;
                    break;
                }
            }
        }

        LOGGER.info("Starting proxy...");
        try {
            this.proxyPort = proxy.start(LOCAL_PROXY_IP);
            LOGGER.info("Proxy started, listening at port [{}].", proxyPort);

            crawljax = new CrawljaxRunner(createCrawljaxConfiguration());
            crawljax.call();
        } catch (ProvisionException e) {
            LOGGER.warn("Failed to start browser {}", target.getOptions().getBrowserId(), e);
            if (View.isInitialised()) {
                ExtensionSelenium extSelenium =
                        Control.getSingleton()
                                .getExtensionLoader()
                                .getExtension(ExtensionSelenium.class);
                String providedBrowserId = target.getOptions().getBrowserId();
                View.getSingleton()
                        .showWarningDialog(
                                extSelenium.getWarnMessageFailedToStart(providedBrowserId, e));
            }
        } catch (IOException e) {
            LOGGER.warn("An error occurred while starting the proxy.", e);
        } catch (Exception e) {
            LOGGER.error(e, e);
        } finally {
            this.running = false;
            LOGGER.info("Stopping proxy...");
            stopProxy();
            LOGGER.info("Proxy stopped.");
            notifyListenersSpiderStoped();
            SpiderEventPublisher.publishScanEvent(ScanEventPublisher.SCAN_STOPPED_EVENT, 0);
            if (authHandler != null) {
                authHandler.disableAuthentication(user);
            }

            LOGGER.info("Finished Crawljax: {}", displayName);
        }
    }

    private void stopProxy() {
        if (proxy != null) {
            try {
                proxy.close();
            } catch (IOException e) {
                LOGGER.debug("An error occurred while stopping the proxy.", e);
            }
            proxy = null;
        }
    }

    /** called by the buttons of the panel to stop the spider */
    public void stopSpider() {
        crawljax.stop();
    }

    public void addSpiderListener(SpiderListener spiderListener) {
        spiderListeners.add(spiderListener);
    }

    public void removeSpiderListener(SpiderListener spiderListener) {
        spiderListeners.remove(spiderListener);
    }

    private void notifyListenersSpiderStarted() {
        for (SpiderListener listener : spiderListeners) {
            listener.spiderStarted();
        }
    }

    private void notifySpiderListenersFoundMessage(
            HistoryReference historyReference, HttpMessage httpMessage, ResourceState state) {
        for (SpiderListener listener : spiderListeners) {
            listener.foundMessage(historyReference, httpMessage, state);
        }
    }

    private void notifyListenersSpiderStoped() {
        for (SpiderListener listener : spiderListeners) {
            listener.spiderStopped();
        }
    }

    private class SpiderProxyListener implements HttpMessageHandler {

        private final List<AllowedResource> allowedResourcesEnabled;

        SpiderProxyListener() {
            allowedResourcesEnabled =
                    target.getOptions().getAllowedResources().stream()
                            .filter(AllowedResource::isEnabled)
                            .collect(Collectors.toList());
        }

        @Override
        public void handleMessage(HttpMessageHandlerContext ctx, HttpMessage httpMessage) {
            if (!ctx.isFromClient()) {
                notifyMessage(
                        httpMessage,
                        HistoryReference.TYPE_SPIDER_AJAX,
                        getResourceState(httpMessage));
                return;
            }

            ResourceState state = ResourceState.PROCESSED;
            final String uri = httpMessage.getRequestHeader().getURI().toString();
            if (allowedResourcesEnabled.stream()
                    .anyMatch(e -> e.getPattern().matcher(uri).matches())) {
                // Nothing to do, state already set to processed.
            } else if (httpPrefixUriValidator != null
                    && !httpPrefixUriValidator.isValid(httpMessage.getRequestHeader().getURI())) {
                LOGGER.debug("Excluding request [{}] not under subtree.", uri);
                state = ResourceState.OUT_OF_SCOPE;
            } else if (target.getContext() != null) {
                if (!target.getContext().isInContext(uri)) {
                    LOGGER.debug("Excluding request [{}] not in specified context.", uri);
                    state = ResourceState.OUT_OF_CONTEXT;
                }
            } else if (target.isInScopeOnly()) {
                if (!session.isInScope(uri)) {
                    LOGGER.debug("Excluding request [{}] not in scope.", uri);
                    state = ResourceState.OUT_OF_SCOPE;
                }
            } else if (!targetHost.equalsIgnoreCase(httpMessage.getRequestHeader().getHostName())) {
                LOGGER.debug("Excluding request [{}] not on target site [{}].", uri, targetHost);
                state = ResourceState.OUT_OF_SCOPE;
            }
            if (state == ResourceState.PROCESSED) {
                for (String regex : exclusionList) {
                    if (Pattern.matches(regex, uri)) {
                        LOGGER.debug("Excluding request [{}] matched regex [{}].", uri, regex);
                        state = ResourceState.EXCLUDED;
                    }
                }
            }

            if (state != ResourceState.PROCESSED) {
                setOutOfScopeResponse(httpMessage);
                notifyMessage(httpMessage, HistoryReference.TYPE_SPIDER_AJAX_TEMPORARY, state);
                ctx.overridden();
                return;
            }

            httpMessage.setRequestingUser(target.getUser());
        }

        private void setOutOfScopeResponse(HttpMessage httpMessage) {
            try {
                httpMessage.setTimeSentMillis(System.currentTimeMillis());
                httpMessage.setTimeElapsedMillis(0);
                httpMessage.setResponseHeader(outOfScopeResponseHeader.toString());
            } catch (HttpMalformedHeaderException ignore) {
                // Setting a valid response header.
            }
            httpMessage.setResponseBody(outOfScopeResponseBody.getBytes());
        }

        private ResourceState getResourceState(HttpMessage httpMessage) {
            if (!httpMessage.isResponseFromTargetHost()) {
                return ResourceState.IO_ERROR;
            }
            return ResourceState.PROCESSED;
        }
    }

    private void notifyMessage(
            final HttpMessage httpMessage, final int historyType, final ResourceState state) {
        try {
            if (extension.getView() != null && !EventQueue.isDispatchThread()) {
                EventQueue.invokeLater(() -> notifyMessage(httpMessage, historyType, state));
                return;
            }

            HistoryReference historyRef = new HistoryReference(session, historyType, httpMessage);
            if (state == ResourceState.PROCESSED) {
                historyRef.setCustomIcon("/resource/icon/10/spiderAjax.png", true);
                session.getSiteTree().addPath(historyRef, httpMessage);
            }

            notifySpiderListenersFoundMessage(historyRef, httpMessage, state);
        } catch (Exception e) {
            LOGGER.error(e);
        }
    }

    // NOTE: The implementation of this class was copied from
    // com.crawljax.browser.WebDriverBrowserBuilder since it's not
    // possible to correctly extend it because of DI issues.
    // Changes:
    // - Changed to use Selenium add-on to leverage the creation of WebDrivers.
    private static class AjaxSpiderBrowserBuilder implements Provider<EmbeddedBrowser> {

        @Inject private CrawljaxConfiguration configuration;
        @Inject private Plugins plugins;

        private final String providedBrowserId;

        public AjaxSpiderBrowserBuilder(String providedBrowserId) {
            super();
            this.providedBrowserId = providedBrowserId;
        }

        /**
         * Build a new WebDriver based EmbeddedBrowser.
         *
         * @return the new build WebDriver based embeddedBrowser
         */
        @Override
        public EmbeddedBrowser get() {
            LOGGER.debug("Setting up a Browser");
            // Retrieve the config values used
            ImmutableSortedSet<String> filterAttributes =
                    configuration.getCrawlRules().getPreCrawlConfig().getFilterAttributeNames();
            long crawlWaitReload = configuration.getCrawlRules().getWaitAfterReloadUrl();
            long crawlWaitEvent = configuration.getCrawlRules().getWaitAfterEvent();

            ExtensionSelenium extSelenium =
                    Control.getSingleton()
                            .getExtensionLoader()
                            .getExtension(ExtensionSelenium.class);
            EmbeddedBrowser embeddedBrowser =
                    WebDriverBackedEmbeddedBrowser.withDriver(
                            extSelenium.getWebDriver(
                                    HttpSender.AJAX_SPIDER_INITIATOR,
                                    providedBrowserId,
                                    configuration.getProxyConfiguration().getHostname(),
                                    configuration.getProxyConfiguration().getPort()),
                            filterAttributes,
                            crawlWaitEvent,
                            crawlWaitReload);
            plugins.runOnBrowserCreatedPlugins(embeddedBrowser);
            return embeddedBrowser;
        }
    }

    /**
     * A {@link com.crawljax.core.plugin.Plugin} that does nothing, used only to suppress log
     * warning when the {@link CrawljaxRunner} is started.
     *
     * @see SpiderThread#createCrawljaxConfiguration()
     * @see SpiderThread#run()
     */
    private static class DummyPlugin implements OnBrowserCreatedPlugin {

        public static final DummyPlugin DUMMY_PLUGIN = new DummyPlugin();

        @Override
        public void onBrowserCreated(EmbeddedBrowser arg0) {
            // Nothing to do.
        }
    }
}
