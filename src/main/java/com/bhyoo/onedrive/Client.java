package com.bhyoo.onedrive;

import com.bhyoo.onedrive.container.Drive;
import com.bhyoo.onedrive.container.items.*;
import com.bhyoo.onedrive.container.items.pointer.BasePointer;
import com.bhyoo.onedrive.container.items.pointer.IdPointer;
import com.bhyoo.onedrive.container.items.pointer.Operator;
import com.bhyoo.onedrive.container.items.pointer.PathPointer;
import com.bhyoo.onedrive.exceptions.ErrorResponseException;
import com.bhyoo.onedrive.exceptions.InternalException;
import com.bhyoo.onedrive.exceptions.InvalidJsonException;
import com.bhyoo.onedrive.network.async.*;
import com.bhyoo.onedrive.network.sync.SyncRequest;
import com.bhyoo.onedrive.network.sync.SyncResponse;
import com.bhyoo.onedrive.utils.AuthServer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.InjectableValues;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.module.afterburner.AfterburnerModule;
import io.netty.handler.codec.http.QueryStringDecoder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.HttpsURLConnection;
import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;

import static com.bhyoo.onedrive.RequestTool.BASE_URL;
import static com.bhyoo.onedrive.container.items.pointer.Operator.UPLOAD_CREATE_SESSION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED;
import static java.net.HttpURLConnection.*;

// TODO: Enhance javadoc

/**
 * @author <a href="mailto:bh322yoo@gmail.com" target="_top">isac322</a>
 */
public class Client {
	public static final String ITEM_ID_PREFIX = "/drive/items/";
	private static final String AUTH_URL = "https://login.microsoftonline.com/common/oauth2/v2.0";
	@NotNull private static final IllegalStateException LOGIN_FIRST = new IllegalStateException("Do login first!!");

	/**
	 * Only one {@code mapper} per a {@code Client} object.<br>
	 * It makes possible to multi client usage
	 */
	private final ObjectMapper mapper;
	@NotNull private final RequestTool requestTool;

	private AuthenticationInfo authInfo;
	@Nullable private String authCode;
	@Nullable private String fullToken;
	@Getter @NotNull private String[] scopes;
	@Getter @NotNull private String clientId;
	@Getter @NotNull private String clientSecret;
	@Getter @NotNull private String redirectURL;

	/**
	 * Construct with auto login.
	 *
	 * @param clientId     Client id that MS gave to programmer for identify programmer's applications.
	 * @param scope        Array of scopes that client requires.
	 * @param redirectURL  Redirect URL that programmer already set in Application setting. It must matches with set
	 *                     one!
	 * @param clientSecret Client secret key that MS gave to programmer.
	 *
	 * @throws InternalException             If fail to create {@link URI} object in auth process.
	 *                                       if it happens it's probably bug, so please report to
	 *                                       <a href="mailto:bh322yoo@gmail.com" target="_top">author</a>.
	 * @throws UnsupportedOperationException If the user default browser is not found, or it fails to be launched, or
	 *                                       the default handler application failed to be launched, or the current
	 *                                       platform does not support the {@link java.awt.Desktop.Action#BROWSE}
	 *                                       action.
	 * @throws RuntimeException              if login is unsuccessful.
	 */
	public Client(@NotNull String clientId, @NotNull String[] scope,
				  @NotNull String redirectURL, @NotNull String clientSecret) {
		this(clientId, scope, redirectURL, clientSecret, true);
	}

	/**
	 * @param clientId     Client id that MS gave to programmer for identify programmer's applications.
	 * @param scope        Array of scopes that client requires.
	 * @param redirectURL  Redirect URL that programmer already set in Application setting. It must matches with set
	 *                     one!
	 * @param clientSecret Client secret key that MS gave to programmer.
	 * @param autoLogin    if {@code true} construct with auto login.
	 *
	 * @throws InternalException             If fail to create {@link URI} object in auth process.
	 *                                       if it happens it's probably bug, so please report to
	 *                                       <a href="mailto:bh322yoo@gmail.com" target="_top">author</a>.
	 * @throws UnsupportedOperationException If the user default browser is not found, or it fails to be launched, or
	 *                                       the default handler application failed to be launched, or the current
	 *                                       platform does not support the {@link java.awt.Desktop.Action#BROWSE}
	 *                                       action.
	 * @throws RuntimeException              if login is unsuccessful.
	 */
	public Client(@NotNull String clientId, @NotNull String[] scope, @NotNull String redirectURL,
				  @NotNull String clientSecret, boolean autoLogin) {
		this.scopes = scope;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.redirectURL = redirectURL;

		mapper = new ObjectMapper();

		InjectableValues.Std clientInjector = new InjectableValues.Std().addValue("OneDriveClient", this);
		mapper.setInjectableValues(clientInjector);

		mapper.registerModule(new AfterburnerModule());


		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

		// in serialization, ignore null values.
		mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

		requestTool = new RequestTool(this, mapper);

		if (autoLogin) login();
	}

	/**
	 * Implementation of
	 * <a href=https://dev.onedrive.com/auth/msa_oauth.htm>detail</a>
	 *
	 * @throws InternalException             If fail to create {@link URI} object in auth process. or the underlying
	 *                                       input source has problems during parsing response body.
	 *                                       if it happens it's probably bug, so please report to
	 *                                       <a href="mailto:bh322yoo@gmail.com" target="_top">author</a>.
	 * @throws UnsupportedOperationException If the user default browser is not found, or it fails to be launched, or
	 *                                       the default handler application failed to be launched, or the current
	 *                                       platform does not support the {@link java.awt.Desktop.Action#BROWSE}
	 *                                       action.
	 * @throws InvalidJsonException          If fail to parse response of login request into json, or even if success
	 *                                       to parse, if there're unexpected value. both caused by server side not by
	 *                                       SDK.
	 * @throws RuntimeException              if login is unsuccessful.
	 */
	public void login() {
		if (!isLogin()) {
			authCode = getCode();
			redeemToken();
		}
	}




	/*
	*************************************************************
	*
	* Regarding authorization
	*
	*************************************************************
	 */


	/**
	 * Implementation of
	 * <a href=https://dev.onedrive.com/auth/msa_oauth.htm#step-1-get-an-authorization-code>detail</a>.<br>
	 * Trying to login and get <a href="https://dev.onedrive.com/auth/msa_oauth.htm#code-flow">accessCode</a> from
	 * server with login information that given when constructing (see
	 * {@link Client#Client(String, String[], String, String)}.)
	 *
	 * @return <b>Access Code</b>({@code this.accessToken}) if successful. Otherwise throw {@link RuntimeException}.
	 *
	 * @throws InternalException             If fail to create {@link URI} object in auth process.
	 *                                       if it happens it's probably bug, so please report to
	 *                                       <a href="mailto:bh322yoo@gmail.com" target="_top">author</a>.
	 * @throws UnsupportedOperationException If the user default browser is not found, or it fails to be launched, or
	 *                                       the default handler application failed to be launched, or the current
	 *                                       platform does not support the {@link java.awt.Desktop.Action#BROWSE}
	 *                                       action.
	 * @throws RuntimeException              if getting <b>Access Code</b> is unsuccessful.
	 */
	@NotNull
	private String getCode() {
		StringBuilder scope = new StringBuilder();
		for (String s : scopes) scope.append("%20").append(s);

		String url = String.format(AUTH_URL + "/authorize?client_id=%s&scope=%s&response_type=code&redirect_uri=%s",
				clientId, scope.toString(), redirectURL)
				.replace(" ", "%20");

		Semaphore answerLock = new Semaphore(1);

		AuthServer server = new AuthServer(answerLock);
		server.start();

		try {
			Desktop.getDesktop().browse(new URI(url));
		}
		catch (URISyntaxException e) {
			throw new InternalException(
					"Fail to create URI object. probably wrong url on SDK code, contact the author", e);
		}
		catch (IOException e) {
			throw new UnsupportedOperationException("Can not find default browser for authentication.", e);
		}

		try {
			answerLock.acquire();
		}
		catch (InterruptedException e) {
			// FIXME: custom exception
			throw new RuntimeException(SyncRequest.NETWORK_ERR_MSG + " Lock Error In " + this.getClass().getName());
		}

		String code = server.close();
		answerLock.release();

		if (code == null) {
			// FIXME: custom exception
			throw new RuntimeException(SyncRequest.NETWORK_ERR_MSG);
		}

		return code;
	}


	/**
	 * Get token from server with login information that given when {@code Client} object was constructed.<br>
	 * And save to their own {@code Client} object.
	 * <a href="https://dev.onedrive.com/auth/msa_oauth.htm#step-3-get-a-new-access-token-or-refresh-token">detail</a>
	 *
	 * @return access token {@code String} that given from server.
	 *
	 * @throws InvalidJsonException If fail to parse response of login request into json, or even if success to parse,
	 *                              if there're unexpected value. both caused by server side not by SDK.
	 * @throws InternalException    if the underlying input source has problems during parsing response body.
	 */
	@NotNull
	private String redeemToken() {
		return getToken(
				String.format("client_id=%s&redirect_uri=%s&client_secret=%s&code=%s&grant_type=authorization_code",
						clientId, redirectURL, clientSecret, authCode));
	}

	/**
	 * Refresh login info (same as access token).<br>
	 * <a href="https://dev.onedrive.com/auth/msa_oauth.htm#step-3-get-a-new-access-token-or-refresh-token">detail</a>
	 *
	 * @return refreshed access token {@code String}.
	 *
	 * @throws IllegalStateException If caller {@code Client} object isn't login yet.
	 * @throws InvalidJsonException  If fail to parse response of login request into json, or even if success to parse,
	 *                               if there're unexpected value. both caused by server side not by SDK.
	 * @throws InternalException     if the underlying input source has problems during parsing response body.
	 */
	@NotNull
	public String refreshLogin() {
		if (!isLogin()) throw LOGIN_FIRST;

		return getToken(
				String.format("client_id=%s&redirect_uri=%s&client_secret=%s&refresh_token=%s&grant_type" +
								"=refresh_token",
						clientId, redirectURL, clientSecret, authInfo.getRefreshToken()));
	}

	/**
	 * Posting login information to server, be granted and get access token from server. and save them to this
	 * {@code Client} object.
	 *
	 * @param httpBody HTTP POST's body that will be sent to server for being granted.
	 *
	 * @return access token {@code String} that given from server.
	 *
	 * @throws InvalidJsonException If fail to parse response of login request into json, or even if success to parse,
	 *                              if there're unexpected value. both caused by server side not by SDK.
	 * @throws InternalException    if the underlying input source has problems during parsing response body.
	 */
	@NotNull
	private String getToken(String httpBody) {
		SyncResponse response = new SyncRequest(AUTH_URL + "/token")
				.setHeader(CONTENT_TYPE, APPLICATION_X_WWW_FORM_URLENCODED)
				.doPost(httpBody);

		try {
			authInfo = requestTool.parseAuthAndHandle(response, HTTP_OK);
		}
		catch (ErrorResponseException e) {
			throw new InternalException("failed to acquire login token. check login info", e);
		}

		this.fullToken = authInfo.getTokenType() + ' ' + authInfo.getAccessToken();

		return authInfo.getAccessToken();
	}

	/**
	 * Check expiration of authentication. if expired, refresh it with {@link Client#refreshLogin()}.
	 *
	 * @throws IllegalStateException If caller {@code Client} object isn't login yet.
	 * @throws InvalidJsonException  If fail to parse response of login request into json, or even if success to parse,
	 *                               if there're unexpected value. both caused by server side not by SDK.
	 * @throws InternalException     if the underlying input source has problems during parsing response body.
	 */
	private void checkExpired() {
		if (!isLogin()) throw LOGIN_FIRST;

		if (isExpired()) refreshLogin();
	}


	// TODO: Enhance javadoc

	/**
	 * @throws ErrorResponseException if raises error while logout.
	 */
	public void logout() throws ErrorResponseException {
		String url = AUTH_URL + "/logout"; // AUTH_URL + "/logout?post_logout_redirect_uri=" + redirectURL;

		SyncResponse response = new SyncRequest(url).doGet();

		// FIXME: is it valid codes?
		if (response.getCode() != HttpsURLConnection.HTTP_OK) {
			String[] split = response.getUrl().getRef().split("&");
			throw new ErrorResponseException(
					HttpsURLConnection.HTTP_OK,
					response.getCode(),
					split[0].substring(split[0].indexOf('=') + 1),
					QueryStringDecoder.decodeComponent(split[1].substring(split[1].indexOf('=') + 1)));
		}

		authCode = null;
		fullToken = null;
		authInfo = null;
	}




	/*
	*************************************************************
	*
	* Regarding drive
	*
	*************************************************************
	 */


	@NotNull
	public Drive getDefaultDrive() throws ErrorResponseException {
		checkExpired();

		SyncResponse response = requestTool.newRequest("/drive").doGet();
		return requestTool.parseDriveAndHandle(response, HTTP_OK);
	}

	@NotNull
	public Drive[] getAllDrive() throws ErrorResponseException {
		checkExpired();

		SyncResponse response = requestTool.newRequest("/drives").doGet();

		return requestTool.parseDrivePageAndHandle(response, HTTP_OK).getValue();
	}




	/*
	*************************************************************
	*
	* Fetching folder
	*
	*************************************************************
	 */


	@NotNull
	public FolderItem getRootDir() throws ErrorResponseException {
		checkExpired();

		SyncResponse response = requestTool.newRequest("/drive/root/?expand=children").doGet();
		return requestTool.parseFolderItemAndHandle(response, HTTP_OK);
	}


	// TODO: Enhance javadoc
	// TODO: handling error if `id`'s item isn't folder item.

	/**
	 * @param id folder's id.
	 *
	 * @return folder object
	 */
	@NotNull
	public FolderItem getFolder(@NotNull String id) throws ErrorResponseException {
		return getFolder(id, true);
	}

	// TODO: handling error if `id`'s item isn't folder item.
	@NotNull
	public FolderItem getFolder(@NotNull String id, boolean childrenFetching) throws ErrorResponseException {
		checkExpired();

		SyncResponse response;

		if (childrenFetching)
			response = requestTool.newRequest(ITEM_ID_PREFIX + id + "?expand=children").doGet();
		else
			response = requestTool.newRequest(ITEM_ID_PREFIX + id).doGet();

		return requestTool.parseFolderItemAndHandle(response, HTTP_OK);
	}

	// TODO: handling error if `pointer`'s item isn't folder item.
	@NotNull
	public FolderItem getFolder(@NotNull BasePointer pointer) throws ErrorResponseException {
		return getFolder(pointer, true);
	}

	// TODO: handling error if `pointer`'s item isn't folder item.
	@NotNull
	public FolderItem getFolder(@NotNull BasePointer pointer, boolean childrenFetching) throws ErrorResponseException {
		checkExpired();

		SyncResponse response;

		if (childrenFetching)
			response = requestTool.newRequest(pointer.toASCIIApi() + "?expand=children").doGet();
		else
			response = requestTool.newRequest(pointer.toASCIIApi()).doGet();

		return requestTool.parseFolderItemAndHandle(response, HTTP_OK);
	}




	/*
	*************************************************************
	*
	* Fetching file
	*
	*************************************************************
	 */


	// TODO: Enhance javadoc

	/**
	 * @param id file id.
	 *
	 * @return file object
	 */
	@NotNull
	public FileItem getFile(@NotNull String id) throws ErrorResponseException {
		checkExpired();

		SyncResponse response = requestTool.newRequest(ITEM_ID_PREFIX + id).doGet();
		return requestTool.parseFileItemAndHandle(response, HTTP_OK);
	}

	@NotNull
	public FileItem getFile(@NotNull BasePointer pointer) throws ErrorResponseException {
		checkExpired();

		SyncResponse response = requestTool.newRequest(pointer.toASCIIApi()).doGet();
		return requestTool.parseFileItemAndHandle(response, HTTP_OK);
	}




	/*
	*************************************************************
	*
	* Fetching item
	*
	* *************************************************************
	 */


	@NotNull
	public BaseItemFuture getItemAsync(@NotNull String id) {
		checkExpired();
		return requestTool.getItemAsync(ITEM_ID_PREFIX + id);
	}

	@NotNull
	public BaseItemFuture getItemAsync(@NotNull BasePointer pointer) {
		checkExpired();
		return requestTool.getItemAsync(pointer.toASCIIApi());
	}

	@NotNull
	public BaseItem getItem(@NotNull String id) throws ErrorResponseException {
		checkExpired();
		return requestTool.getItem(ITEM_ID_PREFIX + id);
	}

	@NotNull
	public BaseItem getItem(@NotNull BasePointer pointer) throws ErrorResponseException {
		checkExpired();
		return requestTool.getItem(pointer.toASCIIApi());
	}

	// FIXME: read
	@NotNull
	public RemoteItem[] getShared() throws ErrorResponseException {
		checkExpired();

		// FIXME: convert to NONE Tree Model
		ArrayNode values = (ArrayNode) requestTool.doGetJson("/drive/sharedWithMe").get("value");

		int size = values.size();
		final RemoteItem[] items = new RemoteItem[size];
		BaseItemFuture[] futures = new BaseItemFuture[size];

		for (int i = 0; i < size; i++) {
			JsonNode id = values.get(i).get("id");

			// if response doesn't have `id` field or `id` isn't text
			assert !(id == null || !id.isTextual()) : i + "th shared element doesn't have `id` field";

			futures[i] = requestTool.getItemAsync(ITEM_ID_PREFIX + id.asText() + "?expand=children");
		}

		for (int i = 0; i < size; i++) {
			futures[i].syncUninterruptibly();
			items[i] = (RemoteItem) futures[i].getNow();
		}

		return items;
	}




	/*
	*************************************************************
	*
	* Coping OneDrive Item
	*
	*************************************************************
	 */


	/**
	 * request to copy {@code srcId} item to new location of {@code destId}.
	 *
	 * @param srcId  item's id that wants to be copied
	 * @param destId location's id that wants to be placed the copied item
	 *
	 * @return URL {@code String} that can monitor status of copying process
	 *
	 * @throws ErrorResponseException if error happens while requesting copying operation. such as invalid copying
	 *                                request
	 * @throws InvalidJsonException   if fail to parse response of copying request into json. it caused by server side
	 *                                not by SDK.
	 */
	@NotNull
	public String copyItem(@NotNull String srcId, @NotNull String destId) throws ErrorResponseException {
		byte[] content = ("{\"parentReference\":{\"id\":\"" + destId + "\"}}").getBytes();
		return copyItem(ITEM_ID_PREFIX + srcId + "/action.copy", content);
	}

	/**
	 * Works just like {@link Client#copyItem(String, String)}} except new name of item can be designated.
	 *
	 * @param newName new name of item that will be copied
	 *
	 * @see Client#copyItem(String, String)
	 */
	@NotNull
	public String copyItem(@NotNull String srcId, @NotNull String destId, @NotNull String newName)
			throws ErrorResponseException {
		byte[] content = ("{\"parentReference\":{\"id\":\"" + destId + "\"},\"name\":\"" + newName + "\"}").getBytes();
		return copyItem(ITEM_ID_PREFIX + srcId + "/action.copy", content);
	}

	@NotNull
	public String copyItem(@NotNull String srcId, @NotNull PathPointer destPath) throws ErrorResponseException {
		byte[] content = ("{\"parentReference\":" + destPath.toJson() + "}").getBytes();
		return copyItem(ITEM_ID_PREFIX + srcId + "/action.copy", content);
	}

	@NotNull
	public String copyItem(@NotNull String srcId, @NotNull PathPointer dest, @NotNull String newName)
			throws ErrorResponseException {
		byte[] content = ("{\"parentReference\":" + dest.toJson() + ",\"name\":\"" + newName + "\"}").getBytes();
		return copyItem(ITEM_ID_PREFIX + srcId + "/action.copy", content);
	}

	@NotNull
	public String copyItem(@NotNull PathPointer srcPath, @NotNull String destId) throws ErrorResponseException {
		byte[] content = ("{\"parentReference\":{\"id\":\"" + destId + "\"}}").getBytes();
		return copyItem(srcPath.resolveOperator(Operator.ACTION_COPY), content);
	}

	@NotNull
	public String copyItem(@NotNull PathPointer srcPath, @NotNull String destId, @NotNull String newName)
			throws ErrorResponseException {
		byte[] content = ("{\"parentReference\":{\"id\":\"" + destId + "\"},\"name\":\"" + newName + "\"}").getBytes();
		return copyItem(srcPath.resolveOperator(Operator.ACTION_COPY), content);
	}

	@NotNull
	public String copyItem(@NotNull BasePointer src, @NotNull BasePointer dest) throws ErrorResponseException {
		byte[] content = ("{\"parentReference\":" + dest.toJson() + "}").getBytes();
		return copyItem(src.resolveOperator(Operator.ACTION_COPY), content);
	}

	@NotNull
	public String copyItem(@NotNull BasePointer src, @NotNull BasePointer dest, @NotNull String newName)
			throws ErrorResponseException {
		byte[] content = ("{\"parentReference\":" + dest.toJson() + ",\"name\":\"" + newName + "\"}").getBytes();
		return copyItem(src.resolveOperator(Operator.ACTION_COPY), content);
	}


	// TODO: end of copying process, is this link will be useless or inaccessible ?

	/**
	 * @param api     OneDrive copying api that contains item's location. Note that it must be ensured that
	 *                {@code api} is a escaped {@code String}
	 * @param content HTTP body
	 *
	 * @return URL {@code String} that can monitor status of copying process
	 *
	 * @throws ErrorResponseException if error happens while requesting copying operation. such as invalid copying
	 *                                request
	 * @throws InvalidJsonException   if fail to parse response of copying request into json. it caused by server side
	 *                                not by SDK.
	 */
	@NotNull
	private String copyItem(@NotNull String api, @NotNull byte[] content) throws ErrorResponseException {
		checkExpired();

		SyncResponse response = requestTool.postMetadata(api, content);

		// if not 202 Accepted raise ErrorResponseException
		requestTool.errorHandling(response, HTTP_ACCEPTED);

		return response.getHeader().get("Location").get(0);
	}




	/*
	*************************************************************
	*
	* Moving OneDrive Item
	*
	*************************************************************
	 */

	@NotNull
	public BaseItem moveItem(@NotNull String srcId, @NotNull String destId) throws ErrorResponseException {
		byte[] content = ("{\"parentReference\":{\"id\":\"" + destId + "\"}}").getBytes();
		return moveItem(ITEM_ID_PREFIX + srcId, content);
	}

	@NotNull
	public BaseItem moveItem(@NotNull String srcId, @NotNull PathPointer destPath) throws ErrorResponseException {
		byte[] content = ("{\"parentReference\":" + destPath.toJson() + "}").getBytes();
		return moveItem(ITEM_ID_PREFIX + srcId, content);
	}

	@NotNull
	public BaseItem moveItem(@NotNull PathPointer srcPath, @NotNull String destId) throws ErrorResponseException {
		byte[] content = ("{\"parentReference\":{\"id\":\"" + destId + "\"}}").getBytes();
		return moveItem(srcPath.toASCIIApi(), content);
	}

	@NotNull
	public BaseItem moveItem(@NotNull BasePointer src, @NotNull BasePointer dest) throws ErrorResponseException {
		byte[] content = ("{\"parentReference\":" + dest.toJson() + "}").getBytes();
		return moveItem(src.toASCIIApi(), content);
	}

	@NotNull
	private BaseItem moveItem(@NotNull String api, @NotNull byte[] content) throws ErrorResponseException {
		checkExpired();

		final CountDownLatch latch = new CountDownLatch(1);
		final BaseItem[] newItem = new BaseItem[1];
		ResponseFuture responseFuture = requestTool.patchMetadataAsync(api, content, new ResponseFutureListener() {
			@Override public void operationComplete(ResponseFuture future) throws Exception {
				newItem[0] = requestTool.parseAndHandle(
						future.response(),
						future.get(),
						HTTP_OK,
						BaseItem.class);
				latch.countDown();
			}
		});

		// responseFuture.syncUninterruptibly();
		try {
			latch.await();
		}
		catch (InterruptedException e) {
			throw new InternalException("Exception occurs while waiting lock in BaseItem#update()", e);
		}

		return newItem[0];
	}




	/*
	*************************************************************
	*
	* Creating folder
	*
	*************************************************************
	 */


	// TODO: Enhance javadoc
	// TODO: Implement '@name.conflictBehavior'

	/**
	 * Implementation of <a href='https://dev.onedrive.com/items/create.htm'>detail</a>.
	 * <p>
	 *
	 * @param parentId Parent ID that creating folder inside.
	 * @param name     New folder name.
	 *
	 * @return New folder's object.
	 *
	 * @throws RuntimeException If creating folder or converting response is fails.
	 */
	@NotNull
	public FolderItem createFolder(@NotNull String parentId, @NotNull String name) throws ErrorResponseException {
		byte[] content = ("{\"name\":\"" + name + "\",\"folder\":{}}").getBytes();
		return createFolder("/drive/items/" + parentId + "/children", content);
	}

	// TODO: Enhance javadoc
	// TODO: Implement '@name.conflictBehavior'

	/**
	 * Implementation of <a href='https://dev.onedrive.com/items/create.htm'>detail</a>.
	 * <p>
	 *
	 * @param parent Parent pointer that creating folder inside. (either ID or path)
	 * @param name   New folder name.
	 *
	 * @return New folder's object.
	 *
	 * @throws RuntimeException If creating folder or converting response is fails.
	 */
	@NotNull
	public FolderItem createFolder(@NotNull BasePointer parent, @NotNull String name) throws ErrorResponseException {
		byte[] content = ("{\"name\":\"" + name + "\",\"folder\":{}}").getBytes();
		return createFolder(parent.resolveOperator(Operator.CHILDREN), content);
	}

	@NotNull
	private FolderItem createFolder(@NotNull String api, @NotNull byte[] content) throws ErrorResponseException {
		checkExpired();

		SyncResponse response = requestTool.postMetadata(api, content);
		return requestTool.parseFolderItemAndHandle(response, HTTP_CREATED);
	}




	/*
	*************************************************************
	*
	* Downloading files
	*
	*************************************************************
	 */


	public void download(@NotNull String fileId, @NotNull Path downloadFolder) throws IOException {
		_downloadAsync(Client.ITEM_ID_PREFIX + fileId, downloadFolder, null).syncUninterruptibly();
	}

	public void download(@NotNull String fileId, @NotNull Path downloadFolder,
						 @NotNull String newName) throws IOException {
		_downloadAsync(Client.ITEM_ID_PREFIX + fileId + "/content", downloadFolder, newName).syncUninterruptibly();
	}

	public void download(@NotNull BasePointer file, @NotNull Path downloadFolder) throws IOException {
		_downloadAsync(file.toASCIIApi(), downloadFolder, null).syncUninterruptibly();
	}

	public void download(@NotNull BasePointer file, @NotNull Path downloadFolder,
						 @NotNull String newName) throws IOException {
		_downloadAsync(file.resolveOperator(Operator.CONTENT), downloadFolder, newName).syncUninterruptibly();
	}


	public DownloadFuture downloadAsync(@NotNull String fileId, @NotNull Path downloadFolder) throws IOException {
		return _downloadAsync(Client.ITEM_ID_PREFIX + fileId, downloadFolder, null);
	}


	public DownloadFuture downloadAsync(@NotNull String fileId, @NotNull Path downloadFolder,
										@Nullable String newName) throws IOException {
		return _downloadAsync(Client.ITEM_ID_PREFIX + fileId + "/content", downloadFolder, newName);
	}

	public DownloadFuture downloadAsync(@NotNull BasePointer pointer,
										@NotNull Path downloadFolder) throws IOException {
		return _downloadAsync(pointer.toASCIIApi(), downloadFolder, null);
	}

	public DownloadFuture downloadAsync(@NotNull BasePointer pointer, @NotNull Path downloadFolder,
										@Nullable String newName) throws IOException {
		return _downloadAsync(pointer.resolveOperator(Operator.CONTENT), downloadFolder, newName);
	}

	private DownloadFuture _downloadAsync(@NotNull String url, @NotNull Path downloadFolder,
										  @Nullable String newName) throws IOException {
		downloadFolder = downloadFolder.toAbsolutePath().normalize();

		// it's illegal if and only if `downloadFolder` exists but not directory.
		if (Files.exists(downloadFolder) && !Files.isDirectory(downloadFolder))
			throw new IllegalArgumentException(downloadFolder + " already exists and isn't folder.");

		Files.createDirectories(downloadFolder);

		String fullUrl = BASE_URL + url;
		try {
			if (newName != null) {
				return new AsyncDownloadClient(requestTool, new URI(fullUrl), downloadFolder, newName).execute();
			}
			else {
				return new AsyncDownloadClient(
						requestTool,
						new URI(fullUrl + "?select=name,@content.downloadUrl"),
						downloadFolder)
						.execute();
			}
		}
		catch (URISyntaxException e) {
			throw new IllegalArgumentException("Wrong url (" + url + "), full URL : \"" + fullUrl + "\".", e);
		}
	}




	/*
	*************************************************************
	*
	* Uploading files
	*
	*************************************************************
	 */


	public UploadFuture uploadFile(@NotNull String parentId, @NotNull Path filePath) throws IOException {
		String rawPath = filePath.toUri().getRawPath();
		String fileName = rawPath.substring(rawPath.lastIndexOf('/') + 1);
		return requestTool.upload(ITEM_ID_PREFIX + parentId + ":/" + fileName + ":/upload.createSession", filePath);
	}

	public UploadFuture uploadFile(@NotNull IdPointer pointer, @NotNull Path filePath) throws IOException {
		String rawPath = filePath.toUri().getRawPath();
		String fileName = rawPath.substring(rawPath.lastIndexOf('/') + 1);
		return requestTool.upload(pointer.toASCIIApi() + ":/" + fileName + ":/upload.createSession", filePath);
	}

	public UploadFuture uploadFile(@NotNull PathPointer pointer, @NotNull Path filePath) throws IOException {
		String fileName = filePath.getFileName().toString();
		return requestTool.upload(pointer.resolve(fileName).resolveOperator(UPLOAD_CREATE_SESSION), filePath);
	}


	/*
	*************************************************************
	*
	* Deleting item
	*
	*************************************************************
	 */


	public void deleteItem(@NotNull String id) throws ErrorResponseException {
		SyncResponse response = requestTool.newRequest(Client.ITEM_ID_PREFIX + id).doDelete();

		// if response isn't 204 No Content
		requestTool.errorHandling(response, HTTP_NO_CONTENT);
	}

	public void deleteItem(@NotNull BasePointer pointer) throws ErrorResponseException {
		SyncResponse response = requestTool.newRequest(pointer.toASCIIApi()).doDelete();

		// if response isn't 204 No Content
		requestTool.errorHandling(response, HTTP_NO_CONTENT);
	}




	/*
	*************************************************************
	*
	* Searching files
	*
	*************************************************************
	 */

	@NotNull
	public ResponsePage<BaseItem> searchItem(String query) throws ErrorResponseException, IOException {
		String rawQuery = URLEncoder.encode(query, "UTF-8");
		SyncResponse response = requestTool.newRequest("/drive/root/view.search?q=" + rawQuery).doGet();

		return requestTool.parseBaseItemPageAndHandle(response, HTTP_OK);
	}





	/*
	*************************************************************
	*
	* Custom Getter
	*
	*************************************************************
	 */


	public boolean isExpired() {
		if (!isLogin()) throw LOGIN_FIRST;
		return System.currentTimeMillis() >= authInfo.getExpiresIn();
	}

	public boolean isLogin() {
		return authCode != null && authInfo != null;
	}

	public @NotNull String getTokenType() {
		checkExpired();
		return authInfo.getTokenType();
	}

	public @NotNull String getAccessToken() {
		checkExpired();
		return authInfo.getAccessToken();
	}

	public @NotNull String getRefreshToken() {
		checkExpired();
		return authInfo.getRefreshToken();
	}

	public @NotNull String getAuthCode() {
		checkExpired();
		//noinspection ConstantConditions
		return authCode;
	}

	public long getExpirationTime() {
		checkExpired();
		return authInfo.getExpiresIn();
	}

	public @NotNull String getFullToken() {
		checkExpired();
		//noinspection ConstantConditions
		return fullToken;
	}

	@JsonIgnore public @NotNull RequestTool requestTool() {return requestTool;}

	@JsonIgnore public @NotNull ObjectMapper mapper() {return mapper;}
}
