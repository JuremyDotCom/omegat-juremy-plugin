package org.omegat.machinetranslators.juremy;

import org.omegat.core.Core;
import org.omegat.core.machinetranslators.BaseTranslate;
import org.omegat.core.machinetranslators.MachineTranslateError;
import org.omegat.gui.exttrans.MTConfigDialog;
import org.omegat.util.HttpConnectionUtils;
import org.omegat.util.Language;

import java.awt.Window;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Support of Juremy match lookup.
 *
 * Doesn't return any results to OmegaT, rather triggers a search in the Juremy interface opened
 * separately.
 *
 * We don't extend from cached translate, since we always want to get pushes (maybe the
 * filters changed in the meantime).
 */
public class JuremyLookup extends BaseTranslate {

    private static final String VERSION = "0.0.1";  // keep in sync with build.gradle.kts
    static final ResourceBundle BUNDLE = ResourceBundle.getBundle("org.omegat.machinetranslators.juremy.Bundle");

    public static final String ALLOW_JUREMY_TRANSLATE = "allow_juremy_translate";

    protected static final String JUREMY_APP_TOKEN = "juremy.app.token";
    private static final Logger LOGGER = LoggerFactory.getLogger(JuremyLookup.class);

    private String temporaryAppToken = null;

    protected static final String JUREMY_URL_BASE = "https://juremy.com";
    protected static final String JUREMY_V1_PATH = "/api/app-push/v1";

    protected static final String PUSH_PATH = "/push";
    protected static final String SETUP_ROUTE_PATH = "/setup-route";

    private static final int MAX_TEXT_LENGTH = 5000;

    /**
     * Not the actual backoff time, but a counter that determines the time.
     * Cleared after a successful request.
     */
    private int backoff = 0;

    /**
     * Register plugins into OmegaT.
     */
    public static void loadPlugins() {
        Core.registerMachineTranslationClass(JuremyLookup.class);
    }

    public static void unloadPlugins() {}

    private final String basePath;

    private boolean didSetupRoute = false;
    private String routeHeaderName;
    private String routeHeaderValue;

    /**
     * Synchronized on this.
     * Used for retrying push request to self-cancel if a newer translation request arrived in the meantime.
     */
    private int currentTranslationSequence = 0;

    public JuremyLookup() {
        basePath = JUREMY_URL_BASE + JUREMY_V1_PATH;
    }

    /**
     * Constructor for tests.
     *
     * @param baseUrl
     *            custom base url
     * @param key
     *            temporary api key
     */
    public JuremyLookup(String baseUrl, String key) {
        basePath = baseUrl + JUREMY_V1_PATH;
        temporaryAppToken = key;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getPreferenceName() {
        return ALLOW_JUREMY_TRANSLATE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return BUNDLE.getString("MT_ENGINE_JUREMY");
    }

    /*  Only present in OmegaT HEAD, not yet in 6.0.0
    @Override
    protected int getMaxTextLength() {
        return MAX_TEXT_LENGTH;
    }
    */

    private void setupRouteAndPing() throws Exception {
        didSetupRoute = false;
        setupRouteIfNeeded();
        final PushRequest req = new PushRequest();
        req.connected = true;
        sendPushRequest(req, Optional.empty());
    }

    private static class RouteResponse {
        public RoutingHeaders routing;
    }

    private static class RoutingHeaders {
        public String header_name;
        public String header_value;
    }

    private void setupRouteIfNeeded() throws Exception {
        if (didSetupRoute) {
            return;
        }
        final Map<String, String> headers = new TreeMap<>();
        populateUserAgentHeader(headers);
        populateAppTokenHeader(headers);

        final String res;
        try {
            res = HttpConnectionUtils.get(basePath + SETUP_ROUTE_PATH, new TreeMap<>(), headers, "UTF-8");
        } catch (IOException e) {
            Optional<Integer> errorCode = getErrorCode(e);
            if (errorCode.isPresent()) {
                switch (errorCode.get()) {
                    case 401:
                        throw new MachineTranslateError(BUNDLE.getString("JUREMY_APP_TOKEN_ERROR"));
                    case 421:
                        // Route setup problem - maybe client is not listening?
                        throw new MachineTranslateError(BUNDLE.getString("JUREMY_ROUTING_SETUP_ERROR"));
                    case 504:
                        throw new MachineTranslateError(BUNDLE.getString("JUREMY_CONNECTION_ERROR"));
                    default:
                        throw e;
                }
            } else {
                throw e;
            }
        }
        final ObjectMapper mapper = new ObjectMapper();
        try {
            final RouteResponse r = mapper.readValue(res.getBytes(StandardCharsets.UTF_8), RouteResponse.class);
            if (r.routing != null) {
                LOGGER.debug("routing.header_name: {}", r.routing.header_name);
                LOGGER.debug("routing.header_value: {}", r.routing.header_value);
            }
            routeHeaderName = r.routing.header_name; // ok to NPE
            routeHeaderValue = r.routing.header_value;
        } catch (Exception e) {
            LOGGER.error("Error parsing routing response", e);
            throw new MachineTranslateError(BUNDLE.getString("JUREMY_ROUTING_RESPONSE_PARSE_ERROR"));
        }
        didSetupRoute = true;
    }

    private void populateUserAgentHeader(Map<String, String> headers) {
        headers.put("User-Agent", "OmegaTJuremySearchPush/" + VERSION);
    }

    /**
     * Note: will overwrite existing values in the output.
     * But that shouldn't be a problem in our case, as request headers are used sparsely.
     */
    private void setupRouteIfNeededAndPopulateRoutingHeader(Map<String, String> output) throws Exception {
        setupRouteIfNeeded();
        output.put(routeHeaderName, routeHeaderValue);
    }

    private void populateAppTokenHeader(Map<String, String> output) throws MachineTranslateError {
        output.put("X-Juremy-App-Token", getAppToken());
    }

    private static class PushRequest {
        public Search search;
        // Can use for initial ping.
        public Boolean connected;
    }

    private static class Search {
        public String src_lang;
        public String dst_lang;
        public String q;
    }

    @Override
    protected String translate(Language sLang, Language tLang, String text) throws Exception {
        final int newSequence = clearSearchStateAndGetNewSequence();
        LOGGER.debug("new search: {}", newSequence);
        return retryTranslate(newSequence, sLang, tLang, text);
    }

    private synchronized int clearSearchStateAndGetNewSequence() {
        // Setting the backoff to zero here has very slight benign race with the previous search, but that's fine.
        // Would be nicer to bundle up the state of a search into an object. If need to add one more state piece, let's
        // do that.
        backoff = 0;
        return ++currentTranslationSequence;
    }

    private synchronized boolean isItStillMySequence(int mySequence) {
        return currentTranslationSequence == mySequence;
    }

    private String retryTranslate(int mySequence, Language sLang, Language tLang, String text) throws Exception {
        if (!waitBackoffCanContinue()) {
            throw new MachineTranslateError(BUNDLE.getString("JUREMY_NO_SUCCESS_AFTER_RETRIES"));
        }
        // Check if we should continue after the backoff. Other way around leaves bigger race window.
        if (!isItStillMySequence(mySequence)) {
            LOGGER.debug("no longer the active search: {}", mySequence);
            return null;
        }
        // There's still some race window for new translation to come in after this point, but that is just the normal
        // flow of events.

        final PushRequest req = new PushRequest();
        req.search = new Search();
        req.search.src_lang = ConversionHelpers.languageTo3Char(sLang);
        req.search.dst_lang = ConversionHelpers.languageTo3Char(tLang);
        req.search.q = limitText(text);

        final Callable<Void> retrier = () -> {
            retryTranslate(mySequence, sLang, tLang, text);
            return null;
        };
        sendPushRequest(req, Optional.of(retrier));
        // We don't serve any results, so return null.
        return null;
    }

    private String limitText(String text) {
        if (text.length() > MAX_TEXT_LENGTH) {
            return text.substring(0, MAX_TEXT_LENGTH);
        }
        return text;
    }

    private void sendPushRequest(PushRequest req, Optional<Callable<Void>> retrier) throws Exception {
        final ObjectMapper objectMapper = new ObjectMapper();
        final String jsonRequestString = objectMapper.writeValueAsString(req);

        Map<String, String> headers = new TreeMap<>();
        setupRouteIfNeededAndPopulateRoutingHeader(headers);
        populateUserAgentHeader(headers);
        populateAppTokenHeader(headers);

        try {
            HttpConnectionUtils.postJSON(basePath + PUSH_PATH, jsonRequestString, headers);
            clearBackoff();
        } catch (IOException e) {
            Optional<Integer> errorCode = getErrorCode(e);
            if (errorCode.isPresent()) {
                switch (errorCode.get()) {
                    case 421:
                        // Either the route needs setting up again, or the client was temporarily not listening,
                        // for example immediately after receiving a previous push. In any case, attempting a route
                        // setup and retrying could help.
                        increaseBackoff();
                        didSetupRoute = false;
                        if (retrier.isPresent()) {
                            retrier.get().call();
                        }
                        break;
                    case 400:
                        throw new MachineTranslateError(BUNDLE.getString("JUREMY_CALLER_ERROR"));
                    case 401:
                        throw new MachineTranslateError(BUNDLE.getString("JUREMY_APP_TOKEN_ERROR"));
                    case 403:
                        throw new MachineTranslateError(BUNDLE.getString("JUREMY_DEVICE_PROBLEM"));
                    case 500:
                        throw new MachineTranslateError(BUNDLE.getString("JUREMY_SERVER_ERROR"));
                    case 504:
                        throw new MachineTranslateError(BUNDLE.getString("JUREMY_CONNECTION_ERROR"));
                    default:
                        throw e;
                }
            } else {
                throw e;
            }
        }
    }

    private boolean waitBackoffCanContinue() {
        if (backoff == 0) {
            return true;
        }
        if (backoff >= 6) {
            backoff = 0;
            return false;
        }
        final double waitMillis = Math.pow(2, backoff + Math.random()) * 100;
        LOGGER.warn("backoff: {} ms", waitMillis);
        try {
            Thread.sleep((long) waitMillis);
        } catch (InterruptedException e) {
            // pass
        }
        return true;
    }

    private void increaseBackoff() {
        backoff += 1;
    }

    private void clearBackoff() {
        backoff = 0;
    }

    private static Optional<Integer> getErrorCode(IOException e) {
        if (e instanceof HttpConnectionUtils.ResponseError) {
            HttpConnectionUtils.ResponseError re = (HttpConnectionUtils.ResponseError) e;
            return Optional.of(re.code);
        }
        return Optional.empty();
    }

    private String getAppToken() throws MachineTranslateError {
        String appToken = getCredential(JUREMY_APP_TOKEN);
        if (appToken == null || appToken.isEmpty()) {
            if (temporaryAppToken == null) {
                throw new MachineTranslateError(BUNDLE.getString("JUREMY_APP_TOKEN_NOTFOUND"));
            }
            appToken = temporaryAppToken;
        }
        return appToken.trim(); // as a copy-paste safety measure
    }

    /**
     * Engine is configurable.
     *
     * @return true
     */
    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public void showConfigurationUI(Window parent) {

        MTConfigDialog dialog = new MTConfigDialog(parent, getName()) {
            @Override
            protected void onConfirm() {
                String key = panel.valueField1.getText().trim();
                boolean temporary = panel.temporaryCheckBox.isSelected();
                setCredential(JUREMY_APP_TOKEN, key, temporary);
                try {
                    setupRouteAndPing();
                } catch (Exception e) {
                    panel.descriptionTextArea.setText(e.getMessage());
                    // Throwing it will prevent the handler from disposing, so the dialog would remain
                    // visible, with the error message showing.
                    throw new RuntimeException(e);
                }
            }
        };

        dialog.panel.valueLabel1.setText(BUNDLE.getString("MT_ENGINE_JUREMY_APP_TOKEN_LABEL"));
        dialog.panel.valueField1.setText(getCredential(JUREMY_APP_TOKEN));

        dialog.panel.valueLabel2.setVisible(false);
        dialog.panel.valueField2.setVisible(false);

        dialog.panel.temporaryCheckBox.setSelected(isCredentialStoredTemporarily(JUREMY_APP_TOKEN));

        dialog.show();
    }
}
