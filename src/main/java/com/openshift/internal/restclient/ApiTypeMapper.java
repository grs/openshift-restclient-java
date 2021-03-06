/******************************************************************************* 
 * Copyright (c) 2016 Red Hat, Inc. 
 * Distributed under license by Red Hat, Inc. All rights reserved. 
 * This program is made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, 
 * and is available at http://www.eclipse.org/legal/epl-v10.html 
 * 
 * Contributors: 
 * Red Hat, Inc. - initial API and implementation 
 ******************************************************************************/
package com.openshift.internal.restclient;

import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang.StringUtils;
import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openshift.internal.restclient.http.HttpClientException;
import com.openshift.internal.restclient.http.NotFoundException;
import com.openshift.internal.restclient.model.properties.ResourcePropertyKeys;
import com.openshift.internal.util.JBossDmrExtentions;
import com.openshift.restclient.IApiTypeMapper;
import com.openshift.restclient.OpenShiftException;
import com.openshift.restclient.ResourceKind;
import com.openshift.restclient.http.IHttpClient;
import com.openshift.restclient.http.IHttpConstants;
import com.openshift.restclient.model.IResource;

/**
 * Typemapper to determine the endpoints for
 * various openshift resources
 * @author jeff.cantrill
 *
 */
public class ApiTypeMapper implements IApiTypeMapper, ResourcePropertyKeys{
	
	private static final String FWD_SLASH = "/";
	private static final String KUBE_API = "api";
	private static final String OS_API = "oapi";
	private static final String API_GROUPS_API = "apis";
	private static final Logger LOGGER = LoggerFactory.getLogger(ApiTypeMapper.class);
	private final String baseUrl;
	private final IHttpClient client;
	private List<VersionedApiResource> resourceEndpoints;
	private Map<String, String> preferedVersion = new HashMap<>(2);
	
	public ApiTypeMapper(String baseUrl, IHttpClient client) {
		this.baseUrl = baseUrl;
		this.client = client;
		preferedVersion.put(KUBE_API, KubernetesAPIVersion.v1.toString());
		preferedVersion.put(OS_API, OpenShiftAPIVersion.v1.toString());
	}

	@Override
	public boolean isSupported(IResource resource) {
		return isSupported(resource.getApiVersion(), resource.getKind());
	}
	
	@Override
	public IVersionedApiResource getEndpointFor(String apiVersion, String kind) {
		init();
		IVersionedApiResource apiresource = endpointFor(apiVersion, kind);
		if(apiresource == null) {
			throw new OpenShiftException("No endpoint found for %s, version %s", kind, apiVersion);
		}
		return apiresource;
	}
	
	private IVersionedApiResource endpointFor(String version, String kind) {
		String[] split = StringUtils.isBlank(version) ? new String [] {} : version.split(FWD_SLASH);
		Optional<IVersionedApiResource> result = null;
		if(split.length <=1) {
			result = Stream.of(KUBE_API, OS_API)
					.map(api->formatEndpointFor(api, (split.length == 0 ? preferedVersion.get(api) : split[0]), kind))
					.filter(e->resourceEndpoints.contains(e))
					.findFirst();
		}else{
			result = Optional.of(formatEndpointFor(API_GROUPS_API, version, ResourceKind.pluralize(kind, true, true)));
		}
		if(result.isPresent()) {
			int index = resourceEndpoints.indexOf(result.get());
			if(index > -1) {
				return resourceEndpoints.get(index);
			}
		}
		return null;
	}

	@Override
	public boolean isSupported(String kind) {
		return isSupported(null, kind);
	}

	@Override
	public boolean isSupported(String version, String kind) {
		init();
		return endpointFor(version, kind) != null;
	}
	
	private IVersionedApiResource formatEndpointFor(String prefix, String version, String kind) {
		return new VersionedApiResource(prefix, version, ResourceKind.pluralize(kind, true, true));
	}
	
	private synchronized void init() {
		if(resourceEndpoints != null) {
			return;
		}
		List<VersionedApiResource> resourceEndpoints = new ArrayList<>();
		Collection<ApiGroup> groups = getLegacyGroups();
		groups.addAll(getApiGroups());
		groups.forEach(g->{
			Collection<String> versions = g.getVersions();
			versions.forEach(v->{
				Collection<ModelNode> resources = getResources(g, v);
				addEndpoints(resourceEndpoints, g.getPrefix(), g.getName(), v, resources);
			});
		});
		this.resourceEndpoints = resourceEndpoints;
	}
	
	private void addEndpoints(List<VersionedApiResource> endpoints, final String prefix, final String apiGroupName, final String version, final Collection<ModelNode> nodes) {
		for (ModelNode node : nodes) {
			String name = node.get(NAME).asString();
			String capability = null;
			if(name.contains(FWD_SLASH)) {
				int first = name.indexOf(FWD_SLASH);
				capability = name.substring(first+1);
				name = name.substring(0, first);
			}
			boolean namespaced = node.get("namespaced").asBoolean();
			VersionedApiResource resource = new VersionedApiResource(prefix, apiGroupName, version, name, node.get(KIND).asString(), namespaced);
			if(!endpoints.contains(resource)) {
				endpoints.add(resource);
			}
			if(capability != null) {
				int index = endpoints.indexOf(resource);
				endpoints.get(index).addCapability(capability);
			}
		}
	}

	private Collection<ApiGroup> getApiGroups(){
		String json = readEndpoint(API_GROUPS_API);
		return ModelNode.fromJSONString(json)
				.get("groups")
				.asList()
				.stream()
				.map(n->new ApiGroup(API_GROUPS_API, n))
				.collect(Collectors.toList());
	}
	
	private Collection<ModelNode> getResources(IApiGroup group, String version){
		String json = readEndpoint(group.pathFor(version));
		if(StringUtils.isBlank(json)) {
			return new ArrayList<>();
		}
		ModelNode node = ModelNode.fromJSONString(json);
		return node.get("resources").asList();
	}
	
	private Collection<ApiGroup> getLegacyGroups(){
		Collection<ApiGroup> groups = new ArrayList<>();
		for(String e: Arrays.asList(KUBE_API,OS_API)) {
			String json = readEndpoint(e);
			ModelNode n = ModelNode.fromJSONString(json);
			groups.add(new LegacyApiGroup(e,n));
		}
		return groups;
	}
	
	private String readEndpoint(final String endpoint) {
		try {
			final URL url = new URL(new URL(this.baseUrl), endpoint);
			LOGGER.debug(url.toString());
			String response = client.get(url, IHttpConstants.DEFAULT_READ_TIMEOUT);
			LOGGER.debug(response);
			return response;
		} catch (MalformedURLException | SocketTimeoutException e) {
			throw new OpenShiftException(e,"");
		//HACK - This gets us around a server issue
		} catch (HttpClientException e) {
			if(e instanceof NotFoundException) {
				throw new com.openshift.restclient.NotFoundException(e);
			}
			LOGGER.error("Unauthorized exception. Can system:anonymous get the API endpoint", e);
			throw e;
		}
	}
	
	static class ApiGroup implements IApiGroup{
		private final ModelNode node;
		private final String prefix;
		private final String path;
		
		ApiGroup(String prefix, ModelNode node) {
			this.prefix = prefix;
			this.node = node;
			StringBuilder builder = new StringBuilder(prefix);
			if(getName() != null) { //null name for k8e or openshift
				builder
				.append(FWD_SLASH)
				.append(getName());
			}
			path = builder.toString();
		}
		
		protected ModelNode getNode() {
			return node;
		}
		
		@Override
		public String getPrefix() {
			return prefix;
		}

		@Override
		public String getName() {
			return JBossDmrExtentions.asString(node, new HashMap<>(), NAME);
		}
		
		@Override
		public Collection<String> getVersions() {
			return JBossDmrExtentions.get(node, new HashMap<>(), "versions").asList()
				.stream().map(n->n.get("version").asString())
				.collect(Collectors.toList());
		}
		
		@Override
		public String getPreferedVersion() {
			return JBossDmrExtentions.asString(node, new HashMap<>(), "preferedVersion.version");
		}
		
		@Override
		public String pathFor(String version) {
			//add check for supported version?
			return String.format("%s/%s", path, version);
		}
	}
	
	static class LegacyApiGroup extends ApiGroup{

		LegacyApiGroup(String prefix, ModelNode node) {
			super(prefix, node);
		}
		
		@Override
		public String getName() {
			return null;
		}

		@Override
		public Collection<String> getVersions() {
			return JBossDmrExtentions.get(getNode(), new HashMap<>(), "versions").asList()
				.stream().map(n->n.asString())
				.collect(Collectors.toList());
		}

		@Override
		public String getPreferedVersion() {
			return OpenShiftAPIVersion.v1.toString();
		}
		
		
	}
	
	static class VersionedApiResource implements IVersionedApiResource {
		
		private final String prefix;
		private final String name;
		private final boolean namespaced;
		private final Collection<String> capabilities = new ArrayList<>();
		private final String version;
		private String apiGroupName;
		private String kind;
		
		VersionedApiResource(String prefix, String version, String name){
			if(version == null) throw new IllegalArgumentException("version can not be null when creating a VersionedApiResource ");
			if(version.contains(FWD_SLASH)) {
				int last = version.lastIndexOf(FWD_SLASH);
				this.apiGroupName = version.substring(0, last);
				version = version.substring(last + 1);
			}
			this.prefix = prefix;
			this.name = name;
			this.version = version;
			this.namespaced = false;
		}
		
		VersionedApiResource(String prefix, String apiGroupName, String version, String name, String kind, boolean namespaced){
			this.prefix = prefix;
			this.name = name;
			this.namespaced = namespaced;
			this.version = version;
			this.apiGroupName = apiGroupName;
			this.kind = kind;
		}
		
		public void addCapability(String capability) {
			capabilities.add(capability);
		}
		
		@Override
		public String getApiGroupName() {
			return apiGroupName;
		}

		@Override
		public String getVersion() {
			return this.version;
		}

		@Override
		public String getPrefix() {
			return prefix;
		}

		@Override
		public String getName() {
			return name;
		}
		

		@Override
		public String getKind() {
			return kind;
		}

		@Override
		public boolean isNamespaced() {
			return namespaced;
		}

		@Override
		public boolean isSupported(String capability) {
			return capabilities.contains(capability);
		}
		
		@Override
		public String toString() {
			return String.format("%s/%s/%s/%s/%s", prefix, apiGroupName, version, name, version,kind);
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((apiGroupName == null) ? 0 : apiGroupName.hashCode());
			result = prime * result + ((name == null) ? 0 : name.hashCode());
			result = prime * result + ((prefix == null) ? 0 : prefix.hashCode());
			result = prime * result + ((version == null) ? 0 : version.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			VersionedApiResource other = (VersionedApiResource) obj;
			if (apiGroupName == null) {
				if (other.apiGroupName != null)
					return false;
			} else if (!apiGroupName.equals(other.apiGroupName))
				return false;
			if (name == null) {
				if (other.name != null)
					return false;
			} else if (!name.equals(other.name))
				return false;
			if (prefix == null) {
				if (other.prefix != null)
					return false;
			} else if (!prefix.equals(other.prefix))
				return false;
			if (version == null) {
				if (other.version != null)
					return false;
			} else if (!version.equals(other.version))
				return false;
			return true;
		}
		
	}
}
