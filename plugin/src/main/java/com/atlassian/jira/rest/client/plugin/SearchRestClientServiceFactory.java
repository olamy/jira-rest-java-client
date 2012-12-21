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

package com.atlassian.jira.rest.client.plugin;

import com.atlassian.httpclient.api.HttpClient;
import com.atlassian.jira.rest.client.api.SearchRestClient;
import com.atlassian.jira.rest.client.internal.async.AsynchronousSearchRestClient;
import com.atlassian.plugin.remotable.api.annotation.PublicComponent;

import java.net.URI;

@PublicComponent(SearchRestClient.class)
public final class SearchRestClientServiceFactory extends AbstractRestClientServiceFactory<SearchRestClient>
{
    @Override
    protected SearchRestClient getService(URI baseUri, HttpClient httpClient)
    {
        return new AsynchronousSearchRestClient(baseUri, httpClient);
    }
}
