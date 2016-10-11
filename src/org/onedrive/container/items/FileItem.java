package org.onedrive.container.items;

import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.val;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.onedrive.network.legacy.HttpsResponse;
import org.onedrive.Client;
import org.onedrive.container.IdentitySet;
import org.onedrive.container.facet.*;
import org.onedrive.exceptions.FileDownFailException;
import org.onedrive.utils.OneDriveRequest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.*;

/**
 * {@// TODO: Enhance javadoc}
 *
 * @author <a href="mailto:yoobyeonghun@gmail.com" target="_top">isac322</a>
 */
@JsonDeserialize(as = FileItem.class)
public class FileItem extends BaseItem {
	@Getter @Nullable protected AudioFacet audio;
	@NotNull protected FileFacet file;
	@Getter @Nullable protected ImageFacet image;
	@Getter @Nullable protected LocationFacet location;
	@Getter @Nullable protected PhotoFacet photo;
	@Getter @Nullable protected VideoFacet video;

	@JsonCreator
	protected FileItem(@JacksonInject("OneDriveClient") Client client,
					   @JsonProperty("id") String id,
					   @JsonProperty("audio") @Nullable AudioFacet audio,
					   @JsonProperty("createdBy") IdentitySet createdBy,
					   @JsonProperty("createdDateTime") String createdDateTime,
					   @JsonProperty("cTag") String cTag,
					   @JsonProperty("deleted") ObjectNode deleted,
					   @JsonProperty("description") String description,
					   @JsonProperty("eTag") String eTag,
					   @JsonProperty("file") @NotNull FileFacet file,
					   @JsonProperty("fileSystemInfo") FileSystemInfoFacet fileSystemInfo,
					   @JsonProperty("image") @Nullable ImageFacet image,
					   @JsonProperty("lastModifiedBy") IdentitySet lastModifiedBy,
					   @JsonProperty("lastModifiedDateTime") String lastModifiedDateTime,
					   @JsonProperty("location") @Nullable LocationFacet location,
					   @JsonProperty("name") @NotNull String name,
					   @JsonProperty("parentReference") @NotNull ItemReference parentReference,
					   @JsonProperty("photo") @Nullable PhotoFacet photo,
					   @JsonProperty("searchResult") @Nullable SearchResultFacet searchResult,
					   @JsonProperty("shared") @Nullable SharedFacet shared,
					   @JsonProperty("sharePointIds") @Nullable SharePointIdsFacet sharePointIds,
					   @JsonProperty("size") long size,
					   @JsonProperty("video") @Nullable VideoFacet video,
					   @JsonProperty("webDavUrl") String webDavUrl,
					   @JsonProperty("webUrl") String webUrl) {
		super(client, id, createdBy, createdDateTime, cTag, deleted, description, eTag, fileSystemInfo,
				lastModifiedBy, lastModifiedDateTime, name, parentReference, searchResult, shared, sharePointIds,
				size, webDavUrl, webUrl);

		this.audio = audio;
		this.file = file;
		this.image = image;
		this.location = location;
		this.photo = photo;
		this.video = video;
	}

	@NotNull
	@Override
	public String getDriveId() {
		assert parentReference != null;
		return parentReference.driveId;
	}

	@Nullable
	@Override
	public String getPath() {
		assert parentReference != null;
		if (parentReference.path == null) return null;
		return parentReference.path + '/' + name;
	}

	public String getCRC32() {
		return this.file.getCrc32Hash();
	}

	public String getSHA1() {
		return this.file.getSha1Hash();
	}

	public String getQuickXorHash() {
		return this.file.getQuickXorHash();
	}

	/**
	 * @see FileItem#download(Path)
	 */
	public void download(@NotNull String path) throws IOException, FileDownFailException {
		this.download(Paths.get(path));
	}

	/**
	 * Download file from OneDrive to {@code path}.<br>
	 * It could be relative path (like . or ..).<br>
	 * If {@code path} is just directory path, automatically naming as {@code getName()}.<br>
	 * <br>
	 * If {@code path} is file path and already exists, it will throw {@link FileAlreadyExistsException}.
	 *
	 * @param path File or folder path. It could be either parent folder(without filename) for download
	 *             or specific file path (with filename).
	 * @throws IOException                If an I/O error occurs, which is possible because the construction of the
	 *                                    canonical pathname may require filesystem queries
	 * @throws SecurityException          If a required system property value cannot be accessed, or if a security
	 *                                    manager exists and its SecurityManager.checkRead method denies read access to
	 *                                    the file
	 * @throws FileAlreadyExistsException If {@code path} is file path and already exists.
	 * @throws FileDownFailException      If fail to download with not 200 OK response.
	 */
	public void download(@NotNull Path path) throws IOException, FileDownFailException {
		path = path.toAbsolutePath();
		Files.createDirectories(path.getParent());

		HttpsResponse response = OneDriveRequest.doGet("/drive/items/" + id + "/content", client.getFullToken());

		if (response.getCode() != 200) {
			throw new FileDownFailException(
					String.format("File download fails with %d %s", response.getCode(), response.getMessage()));
		}

		val fileChannel = AsynchronousFileChannel.open(path,
				StandardOpenOption.CREATE,
				StandardOpenOption.WRITE);

		ByteBuffer contentBuf = ByteBuffer.wrap(response.getContent());

		fileChannel.write(contentBuf, 0);
	}
}