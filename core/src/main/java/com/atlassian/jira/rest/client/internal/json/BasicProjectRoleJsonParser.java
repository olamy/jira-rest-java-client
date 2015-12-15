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
package com.atlassian.jira.rest.client.internal.json;

import com.atlassian.jira.rest.client.api.RestClientException;
import com.atlassian.jira.rest.client.api.domain.BasicProjectRole;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import javax.annotation.Nullable;
import java.util.Collection;

public class BasicProjectRoleJsonParser implements JsonElementParser<Collection<BasicProjectRole>> {

	@Override
	public Collection<BasicProjectRole> parse(@Nullable final JsonElement json) throws JsonParseException {
		return json == null ?
				ImmutableSet.<BasicProjectRole>of() :
				ImmutableSet.copyOf(Iterators.transform(
						JsonParseUtil.getStringKeys(json.getAsJsonObject()),
						new Function<String, BasicProjectRole>() {
							@Override
							public BasicProjectRole apply(@Nullable final String key) {
								try {
									return new BasicProjectRole(JsonParseUtil.parseURI(
											JsonParseUtil.getAsString(json.getAsJsonObject(), key)), key);
								} catch (JsonParseException e) {
									throw new RestClientException(e);
								}
							}
						}
				));
	}


}
