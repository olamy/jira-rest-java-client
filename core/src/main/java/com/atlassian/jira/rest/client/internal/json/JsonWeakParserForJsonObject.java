/*
 * Copyright (C) 2010 Atlassian
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

package com.atlassian.jira.rest.client.internal.json;


import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

class JsonWeakParserForJsonObject<T> implements JsonWeakParser<T> {
	private final JsonElementParser<T> jsonParser;

	public JsonWeakParserForJsonObject(JsonElementParser<T> jsonParser) {
		this.jsonParser = jsonParser;
	}

	private <T> T convert(Object o, Class<T> clazz) throws JsonParseException {
		try {
			return clazz.cast(o);
		} catch (ClassCastException e) {
			throw new JsonParseException("Expected [" + clazz.getSimpleName() + "], but found [" + o.getClass().getSimpleName() + "]");
		}
	}

	@Override
	public T parse(Object o) throws JsonParseException {
		return jsonParser.parse(convert(o, JsonObject.class));
	}
}
