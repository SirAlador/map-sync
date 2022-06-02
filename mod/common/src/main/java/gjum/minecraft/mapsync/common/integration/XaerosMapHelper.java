package gjum.minecraft.mapsync.common.integration;

import gjum.minecraft.mapsync.common.data.ChunkTile;
import net.minecraft.client.Minecraft;

public class XaerosMapHelper {
	private static boolean is_enabled = false;
	public static boolean getIsEnabled() { return is_enabled; }
	public static boolean isNotAvailable() { return !is_enabled; }

	static {
		try {
			Class.forName("xaero.map.core.XaeroWorldMapCore");
			is_enabled = true;
		} catch (NoClassDefFoundError | ClassNotFoundException ignored) {
			is_enabled = false;
		}
	}

	public static boolean isMapping() {
		if (!is_enabled) return false;
		return XaerosMapHelperReal.is_mapping();
	}

	public static boolean updateWithChunkTile(ChunkTile chunkTile) {
		if (!is_enabled) return false;
		if (!isMapping()) {
			return false;
		}
		return XaerosMapHelperReal.update_with_chunk_tile(chunkTile);
	}
}
