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

package com.atlassian.jira.rest.client.internal.jersey;

import com.atlassian.jira.rest.client.GetCreateIssueMetadataOptions;
import com.atlassian.jira.rest.client.IssueRestClient;
import com.atlassian.jira.rest.client.MetadataRestClient;
import com.atlassian.jira.rest.client.ProgressMonitor;
import com.atlassian.jira.rest.client.RestClientException;
import com.atlassian.jira.rest.client.SessionRestClient;
import com.atlassian.jira.rest.client.domain.BasicIssue;
import com.atlassian.jira.rest.client.domain.CimProject;
import com.atlassian.jira.rest.client.domain.Comment;
import com.atlassian.jira.rest.client.domain.Issue;
import com.atlassian.jira.rest.client.domain.ServerInfo;
import com.atlassian.jira.rest.client.domain.Session;
import com.atlassian.jira.rest.client.domain.Transition;
import com.atlassian.jira.rest.client.domain.Votes;
import com.atlassian.jira.rest.client.domain.Watchers;
import com.atlassian.jira.rest.client.domain.input.AttachmentInput;
import com.atlassian.jira.rest.client.domain.input.FieldInput;
import com.atlassian.jira.rest.client.domain.input.IssueInput;
import com.atlassian.jira.rest.client.domain.input.LinkIssuesInput;
import com.atlassian.jira.rest.client.domain.input.TransitionInput;
import com.atlassian.jira.rest.client.domain.input.WorklogInput;
import com.atlassian.jira.rest.client.internal.ServerVersionConstants;
import com.atlassian.jira.rest.client.internal.json.*;
import com.atlassian.jira.rest.client.internal.json.gen.*;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.client.apache.ApacheHttpClient;
import com.sun.jersey.core.header.FormDataContentDisposition;
import com.sun.jersey.multipart.BodyPart;
import com.sun.jersey.multipart.MultiPart;
import com.sun.jersey.multipart.MultiPartMediaTypes;
import com.sun.jersey.multipart.file.FileDataBodyPart;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import javax.annotation.Nullable;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.concurrent.Callable;

/**
 * Jersey-based implementation of IssueRestClient
 *
 * @since v0.1
 */
public class JerseyIssueRestClient extends AbstractJerseyRestClient implements IssueRestClient {

	private static final String FILE_ATTACHMENT_CONTROL_NAME = "file";
	private static final EnumSet<Expandos> DEFAULT_EXPANDS = EnumSet.of(Expandos.NAMES, Expandos.SCHEMA, Expandos.TRANSITIONS);
	private static final Function<Expandos, String> EXPANDO_TO_PARAM = new Function<Expandos, String>() {
		@Override
		public String apply(Expandos from) {
			return from.getFieldName();
		}
	};
	private final SessionRestClient sessionRestClient;
	private final MetadataRestClient metadataRestClient;

	private final IssueJsonParser issueParser = new IssueJsonParser();
	private final BasicIssueJsonParser basicIssueParser = new BasicIssueJsonParser();
	private final JsonObjectParser<Watchers> watchersParser = WatchersJsonParserBuilder.createWatchersParser();
	private final VotesJsonParser votesJsonParser = new VotesJsonParser();
	private final CreateIssueMetadataJsonParser createIssueMetadataJsonParser = new CreateIssueMetadataJsonParser();
	private final TransitionParser transitionParser = new TransitionParser();
	private ServerInfo serverInfo;

	public JerseyIssueRestClient(URI baseUri, ApacheHttpClient client, SessionRestClient sessionRestClient, MetadataRestClient metadataRestClient, boolean followRedirects) {
		super(baseUri, client, followRedirects);
		this.sessionRestClient = sessionRestClient;
		this.metadataRestClient = metadataRestClient;
	}

	private synchronized ServerInfo getVersionInfo(ProgressMonitor progressMonitor) {
		if (serverInfo == null) {
			serverInfo = metadataRestClient.getServerInfo(progressMonitor);
		}
		return serverInfo;
	}

	@Override
	public Watchers getWatchers(URI watchersUri, ProgressMonitor progressMonitor) {
		return getAndParse(watchersUri, watchersParser, progressMonitor);
	}


	@Override
	public Votes getVotes(URI votesUri, ProgressMonitor progressMonitor) {
		return getAndParse(votesUri, votesJsonParser, progressMonitor);
	}

	@Override
	public Issue getIssue(final String issueKey, ProgressMonitor progressMonitor) {
		return getIssue(issueKey, Collections.<Expandos>emptyList(), progressMonitor);
	}

	@Override
	public Issue getIssue(final String issueKey, Iterable<Expandos> expand, ProgressMonitor progressMonitor) {
		final UriBuilder uriBuilder = UriBuilder.fromUri(baseUri);
		final Iterable<Expandos> expands = Iterables.concat(DEFAULT_EXPANDS, expand);
		uriBuilder.path("issue").path(issueKey).queryParam("expand",
				Joiner.on(',').join(Iterables.transform(expands, EXPANDO_TO_PARAM)));
		return getAndParse(uriBuilder.build(), issueParser, progressMonitor);
	}

	@Override
	public Iterable<Transition> getTransitions(final URI transitionsUri, ProgressMonitor progressMonitor) {
		return getAndParse(transitionsUri, transitionParser, progressMonitor);
	}

	@Override
	public Iterable<Transition> getTransitions(final Issue issue, ProgressMonitor progressMonitor) {
		return getTransitions(issue.getTransitionsUri(), progressMonitor);
	}

	@Override
	public void transition(final URI transitionsUri, final TransitionInput transitionInput, final ProgressMonitor progressMonitor) {
		final int buildNumber = getVersionInfo(progressMonitor).getBuildNumber();
		invoke(new Callable<Void>() {
			@Override
			public Void call() throws Exception {
				JSONObject jsonObject = new JSONObject();
				if (buildNumber >= ServerVersionConstants.BN_JIRA_5) {
					jsonObject.put("transition", new JSONObject().put("id", transitionInput.getId()));
				} else {
					jsonObject.put("transition", transitionInput.getId());
				}
				if (transitionInput.getComment() != null) {
					if (buildNumber >= ServerVersionConstants.BN_JIRA_5) {
						jsonObject.put("update", new JSONObject().put("comment",
								new JSONArray().put(new JSONObject().put("add",
										new CommentJsonGenerator(getVersionInfo(progressMonitor))
												.generate(transitionInput.getComment())))));
					} else {
						jsonObject.put("comment", new CommentJsonGenerator(getVersionInfo(progressMonitor))
								.generate(transitionInput.getComment()));
					}
				}
				final Iterable<FieldInput> fields = transitionInput.getFields();
                JSONObject fieldsJs = new IssueUpdateJsonGenerator().generate(fields);
				if (fieldsJs.keys().hasNext()) {
					jsonObject.put("fields", fieldsJs);
				}

				post(transitionsUri, jsonObject, progressMonitor);

				return null;
			}
		});
	}

	@Override
	public void transition(final Issue issue, final TransitionInput transitionInput, final ProgressMonitor progressMonitor) {
		transition(issue.getTransitionsUri(), transitionInput, progressMonitor);
	}

    @Override
    public void update(final Issue issue, final Iterable<FieldInput> fields, ProgressMonitor progressMonitor) {
		put(issue.getSelf(), fields, progressMonitor);
    }

	@Override
	public void removeIssue(URI issueUri, boolean deleteSubtasks, ProgressMonitor progressMonitor) {
		if (deleteSubtasks) {
			delete(UriBuilder.fromUri(issueUri).queryParam("deleteSubtasks", "true").build(), progressMonitor);
		} else {
			delete(issueUri, progressMonitor);
		}
	}

	@Override
	public void removeIssue(BasicIssue issue, boolean deleteSubtasks, ProgressMonitor progressMonitor) {
		removeIssue(issue.getSelf(), deleteSubtasks, progressMonitor);
	}

	@Override
	public void removeIssue(Long issueId, boolean deleteSubtasks, ProgressMonitor progressMonitor) {
		removeIssue(Long.toString(issueId), deleteSubtasks, progressMonitor);
	}

	@Override
	public void removeIssue(String issueKey, boolean deleteSubtasks, ProgressMonitor progressMonitor) {
		UriBuilder uriBuilder = UriBuilder.fromUri(baseUri).path("issue").path(issueKey);
		if (deleteSubtasks) {
			uriBuilder.queryParam("deleteSubtasks", "true");
		}
		delete(uriBuilder.build(), progressMonitor);
	}

	@Override
	public void vote(final URI votesUri, ProgressMonitor progressMonitor) {
		invoke(new Callable<Void>() {
			@Override
			public Void call() throws Exception {
				final WebResource votesResource = client.resource(votesUri);
				votesResource.post();
				return null;
			}
		});
	}

	@Override
	public void unvote(final URI votesUri, ProgressMonitor progressMonitor) {
		invoke(new Callable<Void>() {
			@Override
			public Void call() throws Exception {
				final WebResource votesResource = client.resource(votesUri);
				votesResource.delete();
				return null;
			}
		});

	}

	@Override
	public void addWatcher(final URI watchersUri, @Nullable final String username, ProgressMonitor progressMonitor) {
		invoke(new Callable<Void>() {
			@Override
			public Void call() throws Exception {
				final WebResource.Builder builder = client.resource(watchersUri).type(MediaType.APPLICATION_JSON_TYPE);
				if (username != null) {
					builder.post(JSONObject.quote(username));
				} else {
					builder.post();
				}
				return null;
			}
		});

	}

	private String getLoggedUsername(ProgressMonitor progressMonitor) {
		final Session session = sessionRestClient.getCurrentSession(progressMonitor);
		return session.getUsername();
	}

	@Override
	public void removeWatcher(final URI watchersUri, final String username, final ProgressMonitor progressMonitor) {
		final UriBuilder uriBuilder = UriBuilder.fromUri(watchersUri);
		if (getVersionInfo(progressMonitor).getBuildNumber() >= ServerVersionConstants.BN_JIRA_4_4) {
			uriBuilder.queryParam("username", username);
		} else {
			uriBuilder.path(username).build();
		}
		delete(uriBuilder.build(), progressMonitor);
	}

	@Override
	public void linkIssue(final LinkIssuesInput linkIssuesInput, final ProgressMonitor progressMonitor) {
		final URI uri = UriBuilder.fromUri(baseUri).path("issueLink").build();
		post(uri, new Callable<JSONObject>() {

			@Override
			public JSONObject call() throws Exception {
				return new LinkIssuesInputGenerator(getVersionInfo(progressMonitor)).generate(linkIssuesInput);
			}
		}, progressMonitor);
	}

	@Override
	public void addAttachment(ProgressMonitor progressMonitor, final URI attachmentsUri, final InputStream in, final String filename) {
		addAttachments(progressMonitor, attachmentsUri, new AttachmentInput(filename, in));
	}

	@Override
	public void addAttachments(ProgressMonitor progressMonitor, final URI attachmentsUri, AttachmentInput... attachments) {
		// just to avoid concurrency issues if this arg is mutable
		final ArrayList<AttachmentInput> myAttachments = Lists.newArrayList(attachments);
		invoke(new Callable<Void>() {
			@Override
			public Void call() throws Exception {
				final MultiPart multiPartInput = new MultiPart();
				for (AttachmentInput attachment : myAttachments) {
					BodyPart bp = new BodyPart(attachment.getInputStream(), MediaType.APPLICATION_OCTET_STREAM_TYPE);
					FormDataContentDisposition.FormDataContentDispositionBuilder dispositionBuilder =
							FormDataContentDisposition.name(FILE_ATTACHMENT_CONTROL_NAME);
					dispositionBuilder.fileName(attachment.getFilename());
					final FormDataContentDisposition formDataContentDisposition = dispositionBuilder.build();
					bp.setContentDisposition(formDataContentDisposition);
					multiPartInput.bodyPart(bp);
				}

				postFileMultiPart(multiPartInput, attachmentsUri);
				return null;
			}

		});
	}

	@Override
	public InputStream getAttachment(ProgressMonitor pm, final URI attachmentUri) {
		return invoke(new Callable<InputStream>() {
			@Override
			public InputStream call() throws Exception {
				final WebResource attachmentResource = client.resource(attachmentUri);
				return attachmentResource.get(InputStream.class);
			}
		});
	}

	@Override
	public void addAttachments(ProgressMonitor progressMonitor, final URI attachmentsUri, File... files) {
		final ArrayList<File> myFiles = Lists.newArrayList(files); // just to avoid concurrency issues if this arg is mutable
		invoke(new Callable<Void>() {
			@Override
			public Void call() throws Exception {
				final MultiPart multiPartInput = new MultiPart();
				for (File file : myFiles) {
					FileDataBodyPart fileDataBodyPart = new FileDataBodyPart(FILE_ATTACHMENT_CONTROL_NAME, file);
					multiPartInput.bodyPart(fileDataBodyPart);
				}
				postFileMultiPart(multiPartInput, attachmentsUri);
				return null;
			}

		});

	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void addComment(final ProgressMonitor progressMonitor, final URI commentsUri, final Comment comment) {
		post(commentsUri, new Callable<JSONObject>() {
			@Override
			public JSONObject call() throws Exception {
				return new CommentJsonGenerator(getVersionInfo(progressMonitor)).generate(comment);
			}
		}, progressMonitor);
	}

	private void postFileMultiPart(MultiPart multiPartInput, URI attachmentsUri) {
		final WebResource attachmentsResource = client.resource(attachmentsUri);
		final WebResource.Builder builder = attachmentsResource.type(MultiPartMediaTypes.createFormData());
		builder.header("X-Atlassian-Token", "nocheck"); // this is required by server side REST API
		builder.post(multiPartInput);
	}


	@Override
	public void watch(final URI watchersUri, ProgressMonitor progressMonitor) {
		addWatcher(watchersUri, null, progressMonitor);
	}

	@Override
	public void unwatch(final URI watchersUri, ProgressMonitor progressMonitor) {
		removeWatcher(watchersUri, getLoggedUsername(progressMonitor), progressMonitor);
	}

	@Override
	public BasicIssue createIssue(IssueInput issue, ProgressMonitor progressMonitor) {
		final UriBuilder uriBuilder = UriBuilder.fromUri(baseUri);
		uriBuilder.path("issue");

		return postAndParse(uriBuilder.build(),
				InputGeneratorCallable.create(new IssueInputJsonGenerator(), issue),
				basicIssueParser, progressMonitor);
	}

	@Override
	public Iterable<CimProject> getCreateIssueMetadata(@Nullable GetCreateIssueMetadataOptions options, ProgressMonitor progressMonitor) {

		final UriBuilder uriBuilder = UriBuilder.fromUri(baseUri).path("issue/createmeta");

		if (options != null) {
			if (options.projectIds != null) {
				uriBuilder.queryParam("projectIds", Joiner.on(",").join(options.projectIds));
			}

			if (options.projectKeys != null) {
				uriBuilder.queryParam("projectKeys", Joiner.on(",").join(options.projectKeys));
			}

			if (options.issueTypeIds != null) {
				uriBuilder.queryParam("issuetypeIds", Joiner.on(",").join(options.issueTypeIds));
			}

			final Iterable<String> issueTypeNames = options.issueTypeNames;
			if (issueTypeNames != null) {
				for (final String name : issueTypeNames) {
					uriBuilder.queryParam("issuetypeNames", name);
				}
			}

			final Iterable<String> expandos = options.expandos;
			if (expandos != null && expandos.iterator().hasNext()) {
				uriBuilder.queryParam("expand", Joiner.on(",").join(expandos));
			}
		}

		return getAndParse(uriBuilder.build(), createIssueMetadataJsonParser, progressMonitor);
	}

	@Override
	public void addWorklog(final URI worklogUri, final WorklogInput worklogInput, final ProgressMonitor progressMonitor) {
		final UriBuilder uriBuilder = UriBuilder.fromUri(worklogUri)
				.queryParam("adjustEstimate", worklogInput.getAdjustEstimate().restValue);

		switch (worklogInput.getAdjustEstimate()) {
			case NEW:
				uriBuilder.queryParam("newEstimate", Strings.nullToEmpty(worklogInput.getAdjustEstimateValue()));
				break;
			case MANUAL:
				uriBuilder.queryParam("reduceBy", Strings.nullToEmpty(worklogInput.getAdjustEstimateValue()));
				break;
		}

		post(uriBuilder.build(), new Callable<JSONObject>() {
			@Override
			public JSONObject call() throws Exception {
				return new WorklogInputJsonGenerator().generate(worklogInput);
			}
		}, progressMonitor);
	}
}