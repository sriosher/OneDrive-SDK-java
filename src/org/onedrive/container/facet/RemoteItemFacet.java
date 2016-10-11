package org.onedrive.container.facet;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.onedrive.container.items.ItemReference;

/**
 * https://dev.onedrive.com/facets/remoteitem_facet.htm
 * {@// TODO: Enhance javadoc}
 *
 * @author <a href="mailto:yoobyeonghun@gmail.com" target="_top">isac322</a>
 */
public class RemoteItemFacet {
	@Getter @NotNull protected final String id;
	@Getter @NotNull protected final ItemReference parentReference;
	@Getter @Nullable protected final FolderFacet folder;
	@Getter @Nullable protected final FileFacet file;
	@Getter @Nullable protected final FileSystemInfoFacet fileSystemInfo;
	@Getter protected final long size;
	@Getter @Nullable protected final String name;

	@JsonCreator
	protected RemoteItemFacet(@NotNull @JsonProperty("id") String id,
							  @NotNull @JsonProperty("parentReference") ItemReference parentReference,
							  @Nullable @JsonProperty("folder") FolderFacet folder,
							  @Nullable @JsonProperty("file") FileFacet file,
							  @Nullable @JsonProperty("fileSystemInfo") FileSystemInfoFacet fileSystemInfo,
							  @JsonProperty("size") Long size,
							  @Nullable @JsonProperty("name") String name) {
		if (size == null) {
			throw new RuntimeException("\"size\" filed can not be null");
		}
		this.id = id;
		this.parentReference = parentReference;
		this.folder = folder;
		this.file = file;
		this.fileSystemInfo = fileSystemInfo;
		this.size = size;
		this.name = name;
	}
}