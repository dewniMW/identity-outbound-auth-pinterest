/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

package org.wso2.carbon.identity.authenticator.pinterest;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.oltu.oauth2.client.OAuthClient;
import org.apache.oltu.oauth2.client.URLConnectionClient;
import org.apache.oltu.oauth2.client.request.OAuthClientRequest;
import org.apache.oltu.oauth2.client.response.OAuthAuthzResponse;
import org.apache.oltu.oauth2.client.response.OAuthClientResponse;
import org.apache.oltu.oauth2.common.exception.OAuthProblemException;
import org.apache.oltu.oauth2.common.exception.OAuthSystemException;
import org.apache.oltu.oauth2.common.message.types.GrantType;
import org.apache.oltu.oauth2.common.utils.JSONUtils;
import org.json.JSONObject;
import org.wso2.carbon.identity.application.authentication.framework.FederatedApplicationAuthenticator;
import org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext;
import org.wso2.carbon.identity.application.authentication.framework.exception.ApplicationAuthenticatorException;
import org.wso2.carbon.identity.application.authentication.framework.exception.AuthenticationFailedException;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;
import org.wso2.carbon.identity.application.authenticator.oidc.OIDCAuthenticatorConstants;
import org.wso2.carbon.identity.application.authenticator.oidc.OpenIDConnectAuthenticator;
import org.wso2.carbon.identity.application.common.model.Claim;
import org.wso2.carbon.identity.application.common.model.ClaimMapping;
import org.wso2.carbon.identity.application.common.model.Property;
import org.wso2.carbon.identity.core.util.IdentityIOStreamUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Authenticator of Pinterest
 */
public class PinterestAuthenticator extends OpenIDConnectAuthenticator implements FederatedApplicationAuthenticator {

	private static Log log = LogFactory.getLog(PinterestAuthenticator.class);

	/**
	 * check weather user can process or not.
	 *
	 * @param request the request
	 * @return true or false
	 */
	@Override
	public boolean canHandle(HttpServletRequest request) {
		if (log.isDebugEnabled()) {
			log.debug("Inside PinterestOAuth2Authenticator canHandle method and checking whether the code and state " +
			          "exist");
		}
		return request.getParameter(PinterestAuthenticatorConstants.OAUTH2_GRANT_TYPE_CODE) != null &&
		       request.getParameter(PinterestAuthenticatorConstants.OAUTH2_PARAM_STATE) != null;
	}

	/**
	 * Get Pinterest authorization endpoint.
	 */
	@Override
	protected String getAuthorizationServerEndpoint(Map<String, String> authenticatorProperties) {
		return PinterestAuthenticatorConstants.PINTEREST_OAUTH_ENDPOINT;
	}

	/**
	 * Get Pinterest token endpoint.
	 */
	@Override
	protected String getTokenEndpoint(Map<String, String> authenticatorProperties) {
		return PinterestAuthenticatorConstants.PINTEREST_TOKEN_ENDPOINT;
	}

	/**
	 * Get Pinterest user info endpoint.
	 */
	@Override
	protected String getUserInfoEndpoint(OAuthClientResponse token, Map<String, String> authenticatorProperties) {
		return PinterestAuthenticatorConstants.PINTEREST_USERINFO_ENDPOINT;
	}

	/**
	 * Check ID token in Pinterest OAuth.
	 */
	@Override
	protected boolean requiredIDToken(Map<String, String> authenticatorProperties) {
		return false;
	}

	/**
	 * Get the friendly name of the Authenticator.
	 */
	@Override
	public String getFriendlyName() {
		return PinterestAuthenticatorConstants.PINTEREST_CONNECTOR_FRIENDLY_NAME;
	}

	/**
	 * Get the name of the Authenticator.
	 */
	@Override
	public String getName() {
		return PinterestAuthenticatorConstants.PINTEREST_CONNECTOR_NAME;
	}

	/**
	 * Get Configuration Properties.
	 */
	@Override
	public List<Property> getConfigurationProperties() {
		List<Property> configProperties = new ArrayList<>();

		Property clientId = new Property();
		clientId.setName(OIDCAuthenticatorConstants.CLIENT_ID);
		clientId.setDisplayName(PinterestAuthenticatorConstants.CLIENT_ID);
		clientId.setRequired(true);
		clientId.setDescription("Enter Pinterest IDP client identifier value");
		clientId.setDisplayOrder(0);
		configProperties.add(clientId);

		Property clientSecret = new Property();
		clientSecret.setName(OIDCAuthenticatorConstants.CLIENT_SECRET);
		clientSecret.setDisplayName(PinterestAuthenticatorConstants.CLIENT_SECRET);
		clientSecret.setRequired(true);
		clientSecret.setConfidential(true);
		clientSecret.setDescription("Enter Pinterest IDP client secret value");
		clientSecret.setDisplayOrder(1);
		configProperties.add(clientSecret);

		Property callbackUrl = new Property();
		callbackUrl.setDisplayName("Callback URL");
		callbackUrl.setName(PinterestAuthenticatorConstants.CALLBACK_URL);
		callbackUrl.setDescription("Enter value corresponding to callback url.");
		callbackUrl.setRequired(true);
		callbackUrl.setDisplayOrder(2);
		configProperties.add(callbackUrl);

		return configProperties;
	}

	/**
	 * This is override because of passing the scope in the request.
	 *
	 * @param request  the http request
	 * @param response the http response
	 * @param context  the authentication context
	 * @throws AuthenticationFailedException
	 */
	@Override
	protected void initiateAuthenticationRequest(HttpServletRequest request, HttpServletResponse response,
	                                             AuthenticationContext context) throws AuthenticationFailedException {
		try {
			Map<String, String> authenticatorProperties = context.getAuthenticatorProperties();
			if (authenticatorProperties != null) {
				String clientId = authenticatorProperties.get(OIDCAuthenticatorConstants.CLIENT_ID);
				String authorizationEP = getAuthorizationServerEndpoint(authenticatorProperties);
				String callbackurl = getCallbackUrl(authenticatorProperties);
				String state = context.getContextIdentifier();
				OAuthClientRequest authzRequest;
				authzRequest = OAuthClientRequest.authorizationLocation(authorizationEP).setClientId(clientId)
				                                 .setRedirectURI(callbackurl)
				                                 .setScope(PinterestAuthenticatorConstants.PINTEREST_BASIC_SCOPE)
				                                 .setResponseType(
						                                 PinterestAuthenticatorConstants.OAUTH2_GRANT_TYPE_CODE)
				                                 .setState(state).buildQueryMessage();
				String loginPage = authzRequest.getLocationUri();
				response.sendRedirect(loginPage);
			} else {
				throw new AuthenticationFailedException("Authenticator Properties cannot be null");
			}
		} catch (IOException e) {
			throw new AuthenticationFailedException("Error while sending the redirect response to the client", e);
		} catch (OAuthSystemException e) {
			throw new AuthenticationFailedException("Error while generating the OAuthClientRequest", e);
		}
	}

	/**
	 * Get the CallBackURL.
	 */
	@Override
	protected String getCallbackUrl(Map<String, String> authenticatorProperties) {
		return authenticatorProperties.get(PinterestAuthenticatorConstants.CALLBACK_URL);
	}

	/**
	 * This method are overridden for extra claim request to Pinterest end-point.
	 *
	 * @param request  the http request
	 * @param response the http response
	 * @param context  the authentication context
	 * @throws AuthenticationFailedException
	 */
	@Override
	protected void processAuthenticationResponse(HttpServletRequest request, HttpServletResponse response,
	                                             AuthenticationContext context) throws AuthenticationFailedException {
		try {
			Map<String, String> authenticatorProperties = context.getAuthenticatorProperties();
			String clientId = authenticatorProperties.get(OIDCAuthenticatorConstants.CLIENT_ID);
			String clientSecret = authenticatorProperties.get(OIDCAuthenticatorConstants.CLIENT_SECRET);
			String tokenEndPoint = getTokenEndpoint(authenticatorProperties);
			String callbackUrl = getCallbackUrl(authenticatorProperties);
			OAuthAuthzResponse authzResponse = OAuthAuthzResponse.oauthCodeAuthzResponse(request);
			String code = authzResponse.getCode();
			OAuthClientRequest accessRequest;
			try {
				accessRequest =
						OAuthClientRequest.tokenLocation(tokenEndPoint).setGrantType(GrantType.AUTHORIZATION_CODE)
						                  .setClientId(clientId).setClientSecret(clientSecret)
						                  .setRedirectURI(callbackUrl).setCode(code).buildBodyMessage();
				// create OAuth client that uses custom http client under the hood
				OAuthClient oAuthClient = new OAuthClient(new URLConnectionClient());
				OAuthClientResponse oAuthResponse;
				oAuthResponse = oAuthClient.accessToken(accessRequest);
				String accessToken = oAuthResponse.getParam(PinterestAuthenticatorConstants.ACCESS_TOKEN);
				if (StringUtils.isNotEmpty(accessToken)) {
					Map<ClaimMapping, String> claims = buildClaims(oAuthResponse, authenticatorProperties);
					if (claims != null && !claims.isEmpty()) {
						//Find the subject from the IDP claim mapping, subject Claim URI.
						String subjectFromClaims = FrameworkUtils
								.getFederatedSubjectFromClaims(context.getExternalIdP().getIdentityProvider(), claims);
						setSubjectFromClaims(context, subjectFromClaims, claims);
					} else {
						throw new AuthenticationFailedException("Selected user profile not found");
					}
				} else {
					throw new AuthenticationFailedException("Authentication Failed, access token not available");
				}
			} catch (OAuthSystemException e) {
				throw new AuthenticationFailedException(
						"Exception while building a request for requesting the access token", e);
			} catch (ApplicationAuthenticatorException e) {
				throw new AuthenticationFailedException("Error while building the claim mapping", e);
			}
		} catch (OAuthProblemException e) {
			throw new AuthenticationFailedException("Authentication Failed in OAuthClientResponse", e);
		}
	}

	/**
	 * Extra request sending to Pinterest userinfo end-point.
	 *
	 * @param url         user info endpoint.
	 * @param accessToken access token.
	 * @return user info
	 * @throws ApplicationAuthenticatorException
	 */
	private String getUserInfo(String url, String accessToken) throws ApplicationAuthenticatorException {
		if (log.isDebugEnabled()) {
			log.debug("Sending the request for getting the user info");
		}
		StringBuilder stringBuilder = new StringBuilder();
		BufferedReader bufferedReader = null;
		HttpURLConnection httpConnection = null;
		try {
			URL obj = new URL(url + PinterestAuthenticatorConstants.QUESTION_MARK +
			                  PinterestAuthenticatorConstants.ACCESS_TOKEN + PinterestAuthenticatorConstants.EQUAL +
			                  accessToken);
			URLConnection connection = obj.openConnection();
			// Cast to a HttpURLConnection
			if (connection instanceof HttpURLConnection) {
				httpConnection = (HttpURLConnection) connection;
				httpConnection.setRequestMethod(PinterestAuthenticatorConstants.HTTP_GET_METHOD);
				bufferedReader = new BufferedReader(new InputStreamReader(httpConnection.getInputStream()));
			} else {
				throw new ApplicationAuthenticatorException("Exception while casting the HttpURLConnection");
			}
			String inputLine = bufferedReader.readLine();
			while (inputLine != null) {
				stringBuilder.append(inputLine).append("\n");
				inputLine = bufferedReader.readLine();
			}
		} catch (MalformedURLException e) {
			throw new ApplicationAuthenticatorException("Exception while generating the user info URL", e);
		} catch (ProtocolException e) {
			throw new ApplicationAuthenticatorException("Exception while setting the request method", e);
		} catch (IOException e) {
			throw new ApplicationAuthenticatorException("Exception while reading the response", e);
		} finally {
			IdentityIOStreamUtils.closeReader(bufferedReader);
			if (httpConnection != null) {
				httpConnection.disconnect();
			}
		}
		if (log.isDebugEnabled()) {
			log.debug("Receiving the response for the User info: " + stringBuilder.toString());
		}
		return stringBuilder.toString();
	}

	/**
	 * Get the Pinterest specific claim dialect URI.
	 *
	 * @return Claim dialect URI.
	 */
	@Override
	public String getClaimDialectURI() {
		return PinterestAuthenticatorConstants.CLAIM_DIALECT_URI;
	}

	/**
	 * @param token                   token
	 * @param authenticatorProperties authenticatorProperties
	 * @return claims
	 */
	private Map<ClaimMapping, String> buildClaims(OAuthClientResponse token,
	                                              Map<String, String> authenticatorProperties)
			throws ApplicationAuthenticatorException {
		Map<ClaimMapping, String> claims = new HashMap<>();
		String accessToken = token.getParam("access_token");
		String url = getUserInfoEndpoint(token, authenticatorProperties);
		String json;
		try {
			json = getUserInfo(url, accessToken);

			if (StringUtils.isBlank(json)) {
				if (log.isDebugEnabled()) {
					log.debug("Unable to fetch user claims. Proceeding without user claims");
				}
				return claims;
			}
			JSONObject obj = new JSONObject(json);
			String userData = obj.getJSONObject(PinterestAuthenticatorConstants.ROOT_ELEMENT).toString();
			Map<String, Object> jsonObject = JSONUtils.parseJSON(userData);
			for (Map.Entry<String, Object> data : jsonObject.entrySet()) {
				String key = data.getKey();
				String claimUri = PinterestAuthenticatorConstants.CLAIM_DIALECT_URI +
				                  PinterestAuthenticatorConstants.FORWARD_SLASH + key;
				ClaimMapping claimMapping = new ClaimMapping();
				Claim claim = new Claim();
				claim.setClaimUri(claimUri);
				claimMapping.setRemoteClaim(claim);
				claimMapping.setLocalClaim(claim);
				claims.put(claimMapping, jsonObject.get(key).toString());
				if (log.isDebugEnabled()) {
					log.debug("Adding claims from end-point data mapping : " + key + " - " +
					          jsonObject.get(key).toString());
				}
			}
		} catch (ApplicationAuthenticatorException e) {
			throw new ApplicationAuthenticatorException("Exception while fetching the user claims", e);
		}
		return claims;
	}

	/**
	 * Configure the subject identifier from the claims.
	 *
	 * @param context           AuthenticationContext
	 * @param subjectFromClaims subject identifier claim
	 * @param claims            claims
	 */
	private void setSubjectFromClaims(AuthenticationContext context, String subjectFromClaims,
	                                  Map<ClaimMapping, String> claims) {
		//Use default claim URI on the Authenticator if claim mapping is not defined by the admin
		if (StringUtils.isBlank(subjectFromClaims)) {
			String userId =
					PinterestAuthenticatorConstants.CLAIM_DIALECT_URI + PinterestAuthenticatorConstants.FORWARD_SLASH +
					PinterestAuthenticatorConstants.USER_ID;
			ClaimMapping claimMapping = new ClaimMapping();
			Claim claim = new Claim();
			claim.setClaimUri(userId);
			claimMapping.setRemoteClaim(claim);
			claimMapping.setLocalClaim(claim);
			subjectFromClaims = claims.get(claimMapping);
		}
		AuthenticatedUser authenticatedUserObj =
				AuthenticatedUser.createFederateAuthenticatedUserFromSubjectIdentifier(subjectFromClaims);
		context.setSubject(authenticatedUserObj);
		authenticatedUserObj.setUserAttributes(claims);
	}
}
