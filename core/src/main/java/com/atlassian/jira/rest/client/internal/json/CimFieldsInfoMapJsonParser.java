/*
 * Copyright (C) 2012-2013 Atlassian
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

import com.atlassian.jira.rest.client.api.domain.CimFieldInfo;
import com.atlassian.jira.rest.client.api.domain.FieldSchema;
import com.atlassian.jira.rest.client.api.domain.StandardOperation;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import javax.annotation.Nullable;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON parser that produces Map of String => CimFieldInfo
 *
 * @since v1.0
 */
public class CimFieldsInfoMapJsonParser implements JsonElementParser<Map<String, CimFieldInfo>> {

	private final FieldSchemaJsonParser fieldSchemaJsonParser = new FieldSchemaJsonParser();

	protected final Map<String, JsonElementParser> registeredAllowedValueParsers = new HashMap<String, JsonElementParser>() {{
		put("project", new BasicProjectJsonParser());
		put("version", new VersionJsonParser());
		put("issuetype", new IssueTypeJsonParser());
		put("priority", new BasicPriorityJsonParser());
		put("customFieldOption", new CustomFieldOptionJsonParser());
		put("component", new BasicComponentJsonParser());
		put("resolution", new ResolutionJsonParser());
		put("securitylevel", new SecurityLevelJsonParser());
	}};

	@Override
	public Map<String, CimFieldInfo> parse(JsonElement jsonElement) throws JsonParseException {
		final JsonObject json = jsonElement.getAsJsonObject();

		final Map<String, CimFieldInfo> res = Maps.newHashMapWithExpectedSize(json.entrySet().size());
		for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
			res.put(entry.getKey(), parseIssueFieldInfo(entry.getValue().getAsJsonObject(), entry.getKey()));
		}
		return res;
	}

	private CimFieldInfo parseIssueFieldInfo(JsonObject json, String id) throws JsonParseException {
		final boolean required = json.get("required").getAsBoolean();
		final String name = JsonParseUtil.getOptionalString(json, "name");
		final FieldSchema schema = fieldSchemaJsonParser.parse(json.getAsJsonObject("schema"));
		final Set<StandardOperation> operations = parseOperations(json.getAsJsonArray("operations"));
		final Iterable<Object> allowedValues = parseAllowedValues(json.getAsJsonArray("allowedValues"), schema);
		final URI autoCompleteUri = JsonParseUtil.parseOptionalURI(json, "autoCompleteUrl");

		return new CimFieldInfo(id, required, name, schema, operations, allowedValues, autoCompleteUri);
	}

	private Iterable<Object> parseAllowedValues(@Nullable JsonArray allowedValues, FieldSchema fieldSchema) throws JsonParseException {
		if (allowedValues == null || allowedValues.isJsonNull()) {
			return null;
		}

		if (allowedValues.size() == 0) {
			return Collections.emptyList();
		}

		final JsonElementParser<Object> allowedValuesJsonParser = getParserFor(fieldSchema);
		if (allowedValuesJsonParser != null) {
			JsonArray valuesToParse;
			// fixes for JRADEV-12999
			final boolean isProjectCF = "project".equals(fieldSchema.getType())
					&& "com.atlassian.jira.plugin.system.customfieldtypes:project".equals(fieldSchema.getCustom());
			final boolean isVersionCF = "version".equals(fieldSchema.getType())
					&& "com.atlassian.jira.plugin.system.customfieldtypes:version".equals(fieldSchema.getCustom());
			final boolean isMultiVersionCF = "array".equals(fieldSchema.getType())
					&& "version".equals(fieldSchema.getItems())
					&& "com.atlassian.jira.plugin.system.customfieldtypes:multiversion".equals(fieldSchema.getCustom());

			if ((isProjectCF || isVersionCF || isMultiVersionCF) && allowedValues.get(0).isJsonArray()) {
				valuesToParse = allowedValues.get(0).getAsJsonArray();
			} else {
				valuesToParse = allowedValues;
			}
			return GenericJsonArrayParser.create(allowedValuesJsonParser).parse(valuesToParse);
		} else {
			// fallback - just return collection of JSONObjects
			final int itemsLength = allowedValues.size();
			final List<Object> res = Lists.newArrayListWithExpectedSize(itemsLength);
			for (int i = 0; i < itemsLength; i++) {
				res.add(allowedValues.get(i));
			}
			return res;
		}
	}

	private Set<StandardOperation> parseOperations(JsonArray operations) throws JsonParseException {
		final int operationsCount = operations.size();
		final Set<StandardOperation> res = Sets.newHashSetWithExpectedSize(operationsCount);
		for (int i = 0; i < operationsCount; i++) {
			String opName = operations.get(i).getAsString();
			StandardOperation op = StandardOperation.valueOf(opName.toUpperCase());
			res.add(op);
		}
		return res;
	}

	@Nullable
	private JsonElementParser<Object> getParserFor(FieldSchema fieldSchema) throws JsonParseException {
		final Set<String> customFieldsTypesWithFieldOption = ImmutableSet.of(
				"com.atlassian.jira.plugin.system.customfieldtypes:multicheckboxes",
				"com.atlassian.jira.plugin.system.customfieldtypes:radiobuttons",
				"com.atlassian.jira.plugin.system.customfieldtypes:select",
				"com.atlassian.jira.plugin.system.customfieldtypes:cascadingselect",
				"com.atlassian.jira.plugin.system.customfieldtypes:multiselect"
		);
		String type = "array".equals(fieldSchema.getType()) ? fieldSchema.getItems() : fieldSchema.getType();
		final String custom = fieldSchema.getCustom();
		if (custom != null && customFieldsTypesWithFieldOption.contains(custom)) {
			type = "customFieldOption";
		}
		@SuppressWarnings("unchecked")
		final JsonElementParser<Object> jsonParser = registeredAllowedValueParsers.get(type);
		if (jsonParser == null) {
			return null;
		} else {
			return jsonParser;
		}
	}
}
