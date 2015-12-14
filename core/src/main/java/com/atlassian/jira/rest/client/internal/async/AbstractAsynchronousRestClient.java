/*
 * Copyright (C) 2012 Atlassian
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
package com.atlassian.jira.rest.client.internal.async;

import com.atlassian.httpclient.api.EntityBuilder;
import com.atlassian.httpclient.api.HttpClient;
import com.atlassian.httpclient.api.Response;
import com.atlassian.httpclient.api.ResponsePromise;
import com.atlassian.jira.rest.client.api.domain.util.ErrorCollection;
import com.atlassian.jira.rest.client.internal.json.JsonArrayParser;
import com.atlassian.jira.rest.client.internal.json.JsonElementParser;
import com.atlassian.jira.rest.client.internal.json.JsonParseUtil;
import com.atlassian.jira.rest.client.internal.json.JsonParser;
import com.atlassian.jira.rest.client.api.RestClientException;
import com.atlassian.jira.rest.client.internal.json.gen.JsonGenerator;
import com.atlassian.util.concurrent.Promise;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * This is a base class for asynchronous REST clients.
 *
 * @since v2.0
 */
public abstract class AbstractAsynchronousRestClient {

	private static final String JSON_CONTENT_TYPE = "application/json";

	private final HttpClient client;

	protected AbstractAsynchronousRestClient(HttpClient client) {
		this.client = client;
	}

	protected interface ResponseHandler<T> {
		T handle(Response request) throws IOException;
	}

	protected final <T> Promise<T> getAndParse(final URI uri, final JsonParser<?, T> parser) {
		return callAndParse(client.newRequest(uri).setAccept("application/json").get(), parser);
	}

	protected final <I, T> Promise<T> postAndParse(final URI uri, I entity, final JsonGenerator<I> jsonGenerator,
			final JsonElementParser<T> parser) {
		final ResponsePromise responsePromise = client.newRequest(uri)
				.setEntity(toEntity(jsonGenerator, entity))
				.post();
		return callAndParse(responsePromise, parser);
	}

	protected final <T> Promise<T> postAndParse(final URI uri, final JsonObject entity, final JsonElementParser<T> parser) {
		final ResponsePromise responsePromise = client.newRequest(uri)
				.setEntity(entity.toString())
				.setContentType(JSON_CONTENT_TYPE)
				.post();
		return callAndParse(responsePromise, parser);
	}

	protected final Promise<Void> post(final URI uri, final String entity) {
		final ResponsePromise responsePromise = client.newRequest(uri)
				.setEntity(entity)
				.setContentType(JSON_CONTENT_TYPE)
				.post();
		return call(responsePromise);
	}

	protected final Promise<Void> post(final URI uri, final JsonElement entity) {
		return post(uri, entity.toString());
	}

	protected final <T> Promise<Void> post(final URI uri, final T entity, final JsonGenerator<T> jsonGenerator) {
		final ResponsePromise responsePromise = client.newRequest(uri)
				.setEntity(toEntity(jsonGenerator, entity))
				.post();
		return call(responsePromise);
	}

	protected final Promise<Void> post(final URI uri) {
		return post(uri, "");
	}

	protected final <I, T> Promise<T> putAndParse(final URI uri, I entity, final JsonGenerator<I> jsonGenerator,
			final JsonElementParser<T> parser) {
		final ResponsePromise responsePromise = client.newRequest(uri)
				.setEntity(toEntity(jsonGenerator, entity))
				.put();
		return callAndParse(responsePromise, parser);
	}

	protected final <T> Promise<Void> put(final URI uri, final T entity, final JsonGenerator<T> jsonGenerator) {
		final ResponsePromise responsePromise = client.newRequest(uri)
				.setEntity(toEntity(jsonGenerator, entity))
				.put();
		return call(responsePromise);
	}

	protected final Promise<Void> delete(final URI uri) {
		final ResponsePromise responsePromise = client.newRequest(uri).delete();
		return call(responsePromise);
	}

	protected final <T> Promise<T> callAndParse(final ResponsePromise responsePromise, final ResponseHandler<T> responseHandler) {
		final Function<Response, ? extends T> transformFunction = toFunction(responseHandler);

		return new DelegatingPromise<T>(responsePromise.<T>transform()
				.ok(transformFunction)
				.created(transformFunction)
				.others(AbstractAsynchronousRestClient.<T>errorFunction())
				.toPromise());
	}

	@SuppressWarnings("unchecked")
	protected final <T> Promise<T> callAndParse(final ResponsePromise responsePromise, final JsonParser<?, T> parser) {
		final ResponseHandler<T> responseHandler = new ResponseHandler<T>() {
			@Override
			public T handle(Response response) throws JsonParseException, IOException {
				final String body = response.getEntity();
				return (T) (parser instanceof JsonElementParser ?
						((JsonElementParser) parser).parse(JsonParser.GSON_PARSER.parse(body)) :
						((JsonArrayParser) parser).parse(JsonParser.GSON_PARSER.parse(body)));
			}
		};
		return callAndParse(responsePromise, responseHandler);
	}

	protected final Promise<Void> call(final ResponsePromise responsePromise) {
		return new DelegatingPromise<Void>(responsePromise.<Void>transform()
				.ok(constant((Void) null))
				.created(constant((Void) null))
				.noContent(constant((Void) null))
				.others(AbstractAsynchronousRestClient.<Void>errorFunction())
				.toPromise());
	}

	protected HttpClient client() {
		return client;
	}

	private static <T> Function<Response, T> errorFunction() {
		return new Function<Response, T>() {
			@Override
			public T apply(Response response) {
				try {
					final String body = response.getEntity();
					final Collection<ErrorCollection> errorMessages = extractErrors(response.getStatusCode(), body);
					throw new RestClientException(errorMessages, response.getStatusCode());
				} catch (JsonParseException e) {
					throw new RestClientException(e, response.getStatusCode());
				}
			}
		};
	}

	private static <T> Function<Response, ? extends T> toFunction(final ResponseHandler<T> responseHandler) {
		return new Function<Response, T>() {
			@Override
			public T apply(@Nullable Response input) {
				try {
					return responseHandler.handle(input);
				} catch (JsonParseException e) {
					throw new RestClientException(e);
				} catch (IOException e) {
					throw new RestClientException(e);
				}
			}
		};
	}

	private static <T> Function<Response, T> constant(final T value) {
		return new Function<Response, T>() {
			@Override
			public T apply(Response input) {
				return value;
			}
		};
	}

	static Collection<ErrorCollection> extractErrors(final int status, final String body) throws JsonParseException {
		if (body == null) {
			return Collections.emptyList();
		}
		final JsonObject jsonObject = JsonParser.GSON_PARSER.parse(body).getAsJsonObject();
		final JsonArray issues = jsonObject.getAsJsonArray("issues");
		final ImmutableList.Builder<ErrorCollection> results = ImmutableList.builder();
		if (issues != null && issues.size() == 0) {
			final JsonArray errors = jsonObject.getAsJsonArray("errors");
			for (int i = 0; i < errors.size(); i++) {
				final JsonObject currentJsonObject = errors.get(i).getAsJsonObject();
				results.add(getErrorsFromJson(currentJsonObject.get("status").getAsInt(), currentJsonObject
						.getAsJsonObject("elementErrors")));
			}
		} else {
			results.add(getErrorsFromJson(status, jsonObject));
		}
		return results.build();
	}

	private static ErrorCollection getErrorsFromJson(final int status, final JsonObject jsonObject) throws JsonParseException {
		final JsonObject jsonErrors = jsonObject.getAsJsonObject("errors");
		final JsonArray jsonErrorMessages = jsonObject.getAsJsonArray("errorMessages");

		final Collection<String> errorMessages;
		if (jsonErrorMessages != null) {
			errorMessages = JsonParseUtil.toStringCollection(jsonErrorMessages);
		} else {
			errorMessages = Collections.emptyList();
		}

		final Map<String, String> errors;
		if (jsonErrors != null && jsonErrors.entrySet().size() > 0) {
			errors = JsonParseUtil.toStringMap(jsonErrors);
		} else {
			errors = Collections.emptyMap();
		}
		return new ErrorCollection(status, errorMessages, errors);
	}

	private <T> EntityBuilder toEntity(final JsonGenerator<T> generator, final T bean) {
		return new EntityBuilder() {

			@Override
			public Entity build() {
				return new Entity() {
					@Override
					public Map<String, String> getHeaders() {
						return Collections.singletonMap("Content-Type", JSON_CONTENT_TYPE);
					}

					@Override
					public InputStream getInputStream() {
						try {
							return new ByteArrayInputStream(generator.generate(bean).toString().getBytes(Charset
									.forName("UTF-8")));
						} catch (JsonParseException e) {
							throw new RestClientException(e);
						}
					}
				};
			}
		};
	}
}
