/*
 * oxAuth is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.xdi.oxauth.auth;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.List;

import javax.inject.Inject;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.xdi.model.security.Identity;
import org.xdi.oxauth.model.authorize.AuthorizeRequestParam;
import org.xdi.oxauth.model.common.AuthenticationMethod;
import org.xdi.oxauth.model.common.Prompt;
import org.xdi.oxauth.model.common.SessionIdState;
import org.xdi.oxauth.model.common.SessionState;
import org.xdi.oxauth.model.configuration.AppConfiguration;
import org.xdi.oxauth.model.error.ErrorResponseFactory;
import org.xdi.oxauth.model.exception.InvalidJwtException;
import org.xdi.oxauth.model.registration.Client;
import org.xdi.oxauth.model.token.ClientAssertion;
import org.xdi.oxauth.model.token.ClientAssertionType;
import org.xdi.oxauth.model.token.TokenErrorResponseType;
import org.xdi.oxauth.model.util.Util;
import org.xdi.oxauth.service.ClientFilterService;
import org.xdi.oxauth.service.ClientService;
import org.xdi.oxauth.service.SessionStateService;
import org.xdi.oxauth.util.ServerUtil;
import org.xdi.util.StringHelper;

/**
 * @author Javier Rojas Blum
 * @version March 4, 2016
 */
@WebFilter(asyncSupported = true, urlPatterns = {
		"/seam/resource/restv1/oxauth/authorize", "/seam/resource/restv1/oxauth/token",
		"/seam/resource/restv1/oxauth/userinfo"
		}, displayName = "oxAuth"
)
public class AuthenticationFilter implements Filter {

	@Inject
	private Logger log;

	@Inject
	private Authenticator authenticator;

	@Inject
	private SessionStateService sessionStateService;

	@Inject
	private ClientService clientService;

	@Inject
	private ClientFilterService clientFilterService;

	@Inject
	private ErrorResponseFactory errorResponseFactory;

	@Inject
	private AppConfiguration appConfiguration;

	@Inject
	private Identity identity;

	private String realm;
	public static final String REALM = "oxAuth";

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
	}

	@Override
	public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, final FilterChain filterChain)
			throws IOException, ServletException {
		final HttpServletRequest httpRequest = (HttpServletRequest) servletRequest;
		final HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;

		try {
			final String requestUrl = httpRequest.getRequestURL().toString();
			log.trace("Get request to: '{}'", requestUrl);
			if (requestUrl.endsWith("/token")
					&& ServerUtil.isSameRequestPath(requestUrl, appConfiguration.getTokenEndpoint())) {
				log.debug("Starting token endpoint authentication");
				if (httpRequest.getParameter("client_assertion") != null
						&& httpRequest.getParameter("client_assertion_type") != null) {
					log.debug("Starting JWT token endpoint authentication");
					processJwtAuth(httpRequest, httpResponse, filterChain);
				} else if (httpRequest.getHeader("Authorization") != null
						&& httpRequest.getHeader("Authorization").startsWith("Basic ")) {
					log.debug("Starting Basic Auth token endpoint authentication");
					processBasicAuth(clientService, errorResponseFactory, httpRequest, httpResponse, filterChain);
				} else {
					log.debug("Starting POST Auth token endpoint authentication");
					processPostAuth(clientService, clientFilterService, errorResponseFactory, httpRequest, httpResponse,
							filterChain);
				}
			} else if (httpRequest.getHeader("Authorization") != null) {
				String header = httpRequest.getHeader("Authorization");
				if (header.startsWith("Bearer ")) {
					processBearerAuth(httpRequest, httpResponse, filterChain);
				} else if (header.startsWith("Basic ")) {
					processBasicAuth(clientService, errorResponseFactory, httpRequest, httpResponse, filterChain);
				} else {
					httpResponse.addHeader("WWW-Authenticate", "Basic realm=\"" + getRealm() + "\"");

					httpResponse.sendError(401, "Not authorized");
				}
			} else {
				String sessionState = httpRequest.getParameter(AuthorizeRequestParam.SESSION_STATE);
				List<Prompt> prompts = Prompt.fromString(httpRequest.getParameter(AuthorizeRequestParam.PROMPT), " ");

				if (StringUtils.isBlank(sessionState)) {
					// OXAUTH-297 : check whether session_state is present in
					// cookie
					sessionState = sessionStateService.getSessionStateFromCookie(httpRequest);
				}

				SessionState sessionStateObject = null;
				if (StringUtils.isNotBlank(sessionState)) {
					sessionStateObject = sessionStateService.getSessionState(sessionState);
				}
				if (sessionStateObject != null && SessionIdState.AUTHENTICATED == sessionStateObject.getState()
						&& !prompts.contains(Prompt.LOGIN)) {
					processSessionAuth(errorResponseFactory, sessionState, httpRequest, httpResponse, filterChain);
				} else {
					filterChain.doFilter(httpRequest, httpResponse);
				}
			}
		} catch (IOException ex) {
			log.error(ex.getMessage(), ex);
		} catch (Exception ex) {
			log.error(ex.getMessage(), ex);
		}
	}

	private void processSessionAuth(ErrorResponseFactory errorResponseFactory, String p_sessionState,
			HttpServletRequest p_httpRequest, HttpServletResponse p_httpResponse, FilterChain p_filterChain)
			throws IOException, ServletException {
		boolean requireAuth;

		requireAuth = !authenticator.authenticateBySessionState(p_sessionState);
		log.trace("Process Session Auth, sessionState = {}, requireAuth = {}", p_sessionState, requireAuth);

		if (!requireAuth) {
			try {
				p_filterChain.doFilter(p_httpRequest, p_httpResponse);
			} catch (Exception ex) {
				log.error("Failed to process session authentication", ex);
				requireAuth = true;
			}
		}

		if (requireAuth) {
			sendError(p_httpResponse);
		}
	}

	private void processBasicAuth(ClientService clientService, ErrorResponseFactory errorResponseFactory,
			HttpServletRequest servletRequest, HttpServletResponse servletResponse, FilterChain filterChain) {
		boolean requireAuth = true;

		try {
			String header = servletRequest.getHeader("Authorization");
			if (header != null && header.startsWith("Basic ")) {
				String base64Token = header.substring(6);
				String token = new String(Base64.decodeBase64(base64Token), Util.UTF8_STRING_ENCODING);

				String username = "";
				String password = "";
				int delim = token.indexOf(":");

				if (delim != -1) {
					username = token.substring(0, delim);
					password = token.substring(delim + 1);
				}

				requireAuth = !StringHelper.equals(username, identity.getCredentials().getUsername())
						|| !identity.isLoggedIn();

				// Only authenticate if username doesn't match Identity.username
				// and user isn't authenticated
				if (requireAuth) {
					if (!username.equals(identity.getCredentials().getUsername()) || !identity.isLoggedIn()) {
						if (servletRequest.getRequestURI().endsWith("/token")) {
							Client client = clientService.getClient(username);
							if (client == null
									|| AuthenticationMethod.CLIENT_SECRET_BASIC != client.getAuthenticationMethod()) {
								throw new Exception("The Token Authentication Method is not valid.");
							}
						}

						identity.getCredentials().setUsername(username);
						identity.getCredentials().setPassword(password);

						requireAuth = !authenticator.authenticateWebService();
					}
				}
			}

			if (!requireAuth) {
				filterChain.doFilter(servletRequest, servletResponse);
				return;
			}
		} catch (UnsupportedEncodingException ex) {
			log.info("Basic authentication failed", ex);
		} catch (ServletException ex) {
			log.info("Basic authentication failed", ex);
		} catch (IOException ex) {
			log.info("Basic authentication failed", ex);
		} catch (Exception ex) {
			log.info("Basic authentication failed", ex);
		}

		try {
			if (requireAuth && !identity.isLoggedIn()) {
				sendError(servletResponse);
			}
		} catch (IOException ex) {
			log.error(ex.getMessage(), ex);
		}
	}

	private void processBearerAuth(HttpServletRequest servletRequest, HttpServletResponse servletResponse,
			FilterChain filterChain) {
		try {
			String header = servletRequest.getHeader("Authorization");
			if (header != null && header.startsWith("Bearer ")) {
				// Immutable object
				// servletRequest.getParameterMap().put("access_token", new
				// String[]{accessToken});
				filterChain.doFilter(servletRequest, servletResponse);
			}
		} catch (ServletException ex) {
			log.info("Bearer authorization failed: {}", ex);
		} catch (IOException ex) {
			log.info("Bearer authorization failed: {}", ex);
		} catch (Exception ex) {
			log.info("Bearer authorization failed: {}", ex);
		}
	}

	private void processPostAuth(ClientService clientService, ClientFilterService clientFilterService,
			ErrorResponseFactory errorResponseFactory, HttpServletRequest servletRequest,
			HttpServletResponse servletResponse, FilterChain filterChain) {
		try {
			String clientId = "";
			String clientSecret = "";
			boolean isExistUserPassword = false;
			if (StringHelper.isNotEmpty(servletRequest.getParameter("client_id"))
					&& StringHelper.isNotEmpty(servletRequest.getParameter("client_secret"))) {
				clientId = servletRequest.getParameter("client_id");
				clientSecret = servletRequest.getParameter("client_secret");
				isExistUserPassword = true;
			}
			log.trace("isExistUserPassword: {}", isExistUserPassword);

			boolean requireAuth = !StringHelper.equals(clientId, identity.getCredentials().getUsername())
					|| !identity.isLoggedIn();
			log.debug("requireAuth: '{}'", requireAuth);

			if (requireAuth) {
				if (isExistUserPassword) {
					Client client = clientService.getClient(clientId);
					if (client != null && AuthenticationMethod.CLIENT_SECRET_POST == client.getAuthenticationMethod()) {
						// Only authenticate if username doesn't match
						// Identity.username and user isn't authenticated
						if (!clientId.equals(identity.getCredentials().getUsername()) || !identity.isLoggedIn()) {
							identity.logout();

							identity.getCredentials().setUsername(clientId);
							identity.getCredentials().setPassword(clientSecret);

							requireAuth = !authenticator.authenticateWebService();
						} else {
							authenticator.configureSessionClient(client);
						}
					}
				} else if (Boolean.TRUE.equals(appConfiguration.getClientAuthenticationFiltersEnabled())) {
					String clientDn = clientFilterService
							.processAuthenticationFilters(servletRequest.getParameterMap());
					if (clientDn != null) {
						Client client = clientService.getClientByDn(clientDn);

						identity.logout();

						identity.getCredentials().setUsername(client.getClientId());
						identity.getCredentials().setPassword(null);

						requireAuth = !authenticator.authenticateWebService(true);
					}
				}
			}

			if (!requireAuth) {
				filterChain.doFilter(servletRequest, servletResponse);
				return;
			}

			if (requireAuth && !identity.isLoggedIn()) {
				sendError(servletResponse);
			}
		} catch (ServletException ex) {
			log.error("Post authentication failed: {}", ex);
		} catch (IOException ex) {
			log.error("Post authentication failed: {}", ex);
		} catch (Exception ex) {
			log.error("Post authentication failed: {}", ex);
		}
	}

	private void processJwtAuth(HttpServletRequest servletRequest, HttpServletResponse servletResponse,
			FilterChain filterChain) {
		boolean authorized = false;

		try {
			if (servletRequest.getParameter("client_assertion") != null
					&& servletRequest.getParameter("client_assertion_type") != null) {
				String clientId = servletRequest.getParameter("client_id");
				ClientAssertionType clientAssertionType = ClientAssertionType
						.fromString(servletRequest.getParameter("client_assertion_type"));
				String encodedAssertion = servletRequest.getParameter("client_assertion");

				if (clientAssertionType == ClientAssertionType.JWT_BEARER) {
					ClientAssertion clientAssertion = new ClientAssertion(appConfiguration, clientId,
							clientAssertionType, encodedAssertion);

					String username = clientAssertion.getSubjectIdentifier();
					String password = clientAssertion.getClientSecret();

					// Only authenticate if username doesn't match
					// Identity.username and user isn't authenticated
					if (!username.equals(identity.getCredentials().getUsername()) || !identity.isLoggedIn()) {
						identity.getCredentials().setUsername(username);
						identity.getCredentials().setPassword(password);

						authenticator.authenticateWebService(true);
						authorized = true;
					}
				}
			}

			filterChain.doFilter(servletRequest, servletResponse);
		} catch (ServletException ex) {
			log.info("JWT authentication failed: {}", ex);
		} catch (IOException ex) {
			log.info("JWT authentication failed: {}", ex);
		} catch (InvalidJwtException ex) {
			log.info("JWT authentication failed: {}", ex);
		}

		try {
			if (!authorized) {
				sendError(servletResponse);
			}
		} catch (IOException ex) {
		}
	}

	private void sendError(HttpServletResponse servletResponse) throws IOException {
		PrintWriter out = null;
		try {
			out = servletResponse.getWriter();

			servletResponse.setStatus(401);
			servletResponse.addHeader("WWW-Authenticate", "Basic realm=\"" + getRealm() + "\"");
			servletResponse.setContentType("application/json;charset=UTF-8");
			out.write(errorResponseFactory.getErrorAsJson(TokenErrorResponseType.INVALID_CLIENT));
		} catch (IOException ex) {
			log.error(ex.getMessage(), ex);
		} finally {
			if (out != null) {
				out.close();
			}
		}
	}

	public String getRealm() {
		if (realm != null) {
			return realm;
		} else {
			return REALM;
		}
	}

	public void setRealm(String realm) {
		this.realm = realm;
	}

	@Override
	public void destroy() {
	}

}