package jetbrains.buildServer.auth.saml.plugin;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.controllers.AuthorizationInterceptor;
import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import lombok.var;
import org.apache.commons.validator.routines.RegexValidator;
import org.apache.commons.validator.routines.UrlValidator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class SamlLoginController extends BaseController {

    private final RegexValidator anyAuthorityValidator;
    private SamlAuthenticationScheme samlAuthenticationScheme;
    private final SamlPluginSettingsStorage settingsStorage;

    private final Logger LOG = Loggers.SERVER;

    public SamlLoginController(@NotNull SBuildServer server,
                               @NotNull WebControllerManager webControllerManager,
                               @NotNull AuthorizationInterceptor interceptor,
                               @NotNull SamlAuthenticationScheme samlAuthenticationScheme,
                               @NotNull SamlPluginSettingsStorage settingsStorage) {
        super(server);
        this.samlAuthenticationScheme = samlAuthenticationScheme;
        this.settingsStorage = settingsStorage;
        this.anyAuthorityValidator = new RegexValidator(".*");

        interceptor.addPathNotRequiringAuth(SamlPluginConstants.SAML_INITIATE_LOGIN_URL);
        webControllerManager.registerController(SamlPluginConstants.SAML_INITIATE_LOGIN_URL, this);
    }

    public boolean validateUrl(String url) {
        return new UrlValidator(anyAuthorityValidator, UrlValidator.ALLOW_ALL_SCHEMES + UrlValidator.ALLOW_LOCAL_URLS).isValid(url);
    }

    @Nullable
    @Override
    protected ModelAndView doHandle(@NotNull HttpServletRequest httpServletRequest, @NotNull HttpServletResponse httpServletResponse) throws Exception {

        try {
            LOG.info(String.format("Initiating SSO login at %s", httpServletRequest.getRequestURL()));

            // Ensure the stale TeamCity session cookie doesn't force extra IdP auth.
            // Some browsers may keep TCSESSIONID after TeamCity logout. Clear it proactively
            // when SSO login is initiated to avoid double authentication on IdP side.
            try {
                javax.servlet.http.Cookie cookie = new javax.servlet.http.Cookie("TCSESSIONID", "");
                cookie.setPath("/");
                cookie.setMaxAge(0); // expire immediately
                cookie.setHttpOnly(true);
                cookie.setSecure(httpServletRequest.isSecure());
                httpServletResponse.addCookie(cookie);
            } catch (Throwable t) {
                LOG.warn("Failed to clear TCSESSIONID cookie before SSO initiation: " + t.getMessage(), t);
            }

            var settings = settingsStorage.load();

            var endpoint = settings.getSsoEndpoint();

            if (endpoint == null || "".equals(endpoint.trim())) {
                throw new Exception("You must configure a valid SSO endpoint");
            }

            boolean ssoEndpointIsValid = validateUrl(endpoint);
            if (!ssoEndpointIsValid) {
                throw new Exception(String.format("SSO endpoint (%s) must be a valid URL ", endpoint));
            }

            LOG.info(String.format("Building AuthNRequest to %s", endpoint));
            this.samlAuthenticationScheme.sendAuthnRequest(httpServletRequest, httpServletResponse);
            return null;
        } catch (Exception e) {
            LOG.error(String.format("Error while initiating SSO login redirect: %s", e.getMessage()), e);
            throw e;
        }
    }
}
