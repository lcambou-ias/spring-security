/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.security.oauth2.server.resource.introspection;

import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.nimbusds.oauth2.sdk.TokenIntrospectionResponse;
import com.nimbusds.oauth2.sdk.TokenIntrospectionSuccessResponse;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.Audience;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.support.BasicAuthenticationInterceptor;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import static org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionClaimNames.AUDIENCE;
import static org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionClaimNames.CLIENT_ID;
import static org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionClaimNames.EXPIRES_AT;
import static org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionClaimNames.ISSUED_AT;
import static org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionClaimNames.ISSUER;
import static org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionClaimNames.NOT_BEFORE;
import static org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionClaimNames.SCOPE;

/**
 * A Nimbus implementation of {@link OAuth2TokenIntrospectionClient}.
 *
 * @author Josh Cummings
 * @since 5.2
 */
public class NimbusOAuth2TokenIntrospectionClient implements OAuth2TokenIntrospectionClient {
	private URI introspectionUri;
	private RestOperations restOperations;

	/**
	 * Creates a {@code OAuth2IntrospectionAuthenticationProvider} with the provided parameters
	 *
	 * @param introspectionUri The introspection endpoint uri
	 * @param clientId The client id authorized to introspect
	 * @param clientSecret The client's secret
	 */
	public NimbusOAuth2TokenIntrospectionClient(String introspectionUri, String clientId, String clientSecret) {
		Assert.notNull(introspectionUri, "introspectionUri cannot be null");
		Assert.notNull(clientId, "clientId cannot be null");
		Assert.notNull(clientSecret, "clientSecret cannot be null");

		this.introspectionUri = URI.create(introspectionUri);
		RestTemplate restTemplate = new RestTemplate();
		restTemplate.getInterceptors().add(new BasicAuthenticationInterceptor(clientId, clientSecret));
		this.restOperations = restTemplate;
	}

	/**
	 * Creates a {@code OAuth2IntrospectionAuthenticationProvider} with the provided parameters
	 *
	 * The given {@link RestOperations} should perform its own client authentication against the
	 * introspection endpoint.
	 *
	 * @param introspectionUri The introspection endpoint uri
	 * @param restOperations The client for performing the introspection request
	 */
	public NimbusOAuth2TokenIntrospectionClient(String introspectionUri, RestOperations restOperations) {
		Assert.notNull(introspectionUri, "introspectionUri cannot be null");
		Assert.notNull(restOperations, "restOperations cannot be null");

		this.introspectionUri = URI.create(introspectionUri);
		this.restOperations = restOperations;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Map<String, Object> introspect(String token) {
		TokenIntrospectionSuccessResponse response = Optional.of(token)
				.map(this::buildRequest)
				.map(this::makeRequest)
				.map(this::adaptToNimbusResponse)
				.map(this::parseNimbusResponse)
				.map(this::castToNimbusSuccess)
				// relying solely on the authorization server to validate this token (not checking 'exp', for example)
				.filter(TokenIntrospectionSuccessResponse::isActive)
				.orElseThrow(() -> new OAuth2IntrospectionException("Provided token [" + token + "] isn't active"));
		return convertClaimsSet(response);
	}

	private RequestEntity<MultiValueMap<String, String>> buildRequest(String token) {
		HttpHeaders headers = requestHeaders();
		MultiValueMap<String, String> body = requestBody(token);
		return new RequestEntity<>(body, headers, HttpMethod.POST, this.introspectionUri);
	}

	private HttpHeaders requestHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON_UTF8));
		return headers;
	}

	private MultiValueMap<String, String> requestBody(String token) {
		MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
		body.add("token", token);
		return body;
	}

	private ResponseEntity<String> makeRequest(RequestEntity<?> requestEntity) {
		try {
			return this.restOperations.exchange(requestEntity, String.class);
		} catch (Exception ex) {
			throw new OAuth2IntrospectionException(ex.getMessage(), ex);
		}
	}

	private HTTPResponse adaptToNimbusResponse(ResponseEntity<String> responseEntity) {
		HTTPResponse response = new HTTPResponse(responseEntity.getStatusCodeValue());
		response.setHeader(HttpHeaders.CONTENT_TYPE, responseEntity.getHeaders().getContentType().toString());
		response.setContent(responseEntity.getBody());

		if (response.getStatusCode() != HTTPResponse.SC_OK) {
			throw new OAuth2IntrospectionException(
					"Introspection endpoint responded with " + response.getStatusCode());
		}
		return response;
	}

	private TokenIntrospectionResponse parseNimbusResponse(HTTPResponse response) {
		try {
			return TokenIntrospectionResponse.parse(response);
		} catch (Exception ex) {
			throw new OAuth2IntrospectionException(ex.getMessage(), ex);
		}
	}

	private TokenIntrospectionSuccessResponse castToNimbusSuccess(TokenIntrospectionResponse introspectionResponse) {
		if (!introspectionResponse.indicatesSuccess()) {
			throw new OAuth2IntrospectionException("Token introspection failed");
		}
		return (TokenIntrospectionSuccessResponse) introspectionResponse;
	}

	private Map<String, Object> convertClaimsSet(TokenIntrospectionSuccessResponse response) {
		Map<String, Object> claims = response.toJSONObject();
		if (response.getAudience() != null) {
			List<String> audience = response.getAudience().stream()
					.map(Audience::getValue).collect(Collectors.toList());
			claims.put(AUDIENCE, Collections.unmodifiableList(audience));
		}
		if (response.getClientID() != null) {
			claims.put(CLIENT_ID, response.getClientID().getValue());
		}
		if (response.getExpirationTime() != null) {
			Instant exp = response.getExpirationTime().toInstant();
			claims.put(EXPIRES_AT, exp);
		}
		if (response.getIssueTime() != null) {
			Instant iat = response.getIssueTime().toInstant();
			claims.put(ISSUED_AT, iat);
		}
		if (response.getIssuer() != null) {
			claims.put(ISSUER, issuer(response.getIssuer().getValue()));
		}
		if (response.getNotBeforeTime() != null) {
			claims.put(NOT_BEFORE, response.getNotBeforeTime().toInstant());
		}
		if (response.getScope() != null) {
			claims.put(SCOPE, Collections.unmodifiableList(response.getScope().toStringList()));
		}

		return claims;
	}

	private URL issuer(String uri) {
		try {
			return new URL(uri);
		} catch (Exception ex) {
			throw new OAuth2IntrospectionException("Invalid " + ISSUER + " value: " + uri);
		}
	}
}
