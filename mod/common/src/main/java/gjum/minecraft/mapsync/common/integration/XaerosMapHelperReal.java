package gjum.minecraft.mapsync.common.integration;

import gjum.minecraft.mapsync.common.data.ChunkTile;
import xaero.map.core.XaeroWorldMapCore;
import xaero.map.region.MapTileChunk;

public class XaerosMapHelperReal {
	public static void update_with_chunk_tile(ChunkTile source) {
		//Xaeros leaflet regions are 8x8 chunks
		int xaeros_region_x = source.x() >> 3;
		int xaeros_region_z = source.z() >> 3;
		//The chunk position within the leaflet
		int xaeros_tile_x = source.x() & 7;
		int xaeros_tile_z = source.z() & 7;

		var mapProcessor = XaeroWorldMapCore.currentSession.getMapProcessor();
		//Create doesn't guarentee creation of a new region, just specifies that it's an option.
		//Evidently it's an expensive operation or smth
		var region = mapProcessor.getMapRegion(xaeros_region_x, xaeros_region_z, true);

		//Ensure nothing else makes map changes while this happens
		synchronized (region.writerThreadPauseSync) {
			var tileChunk = new MapTileChunk(region, chunkTile.x(), chunkTile.z());
			region.setChunk(tileChunkLocalX, tileChunkLocalZ, tileChunk);
			tileChunk.setLoadState((byte) 2);

			mapProcessor.getMapSaveLoad().requestLoad(region, "writing");

			int chunkX = tileChunkX * 4 + insideX;
			int chunkZ = tileChunkZ * 4 + insideZ;
			var mapTile = tileChunk.getTile(insideX, insideZ);

			var pixel = mapTile.isLoaded() ? mapTile.getBlock(x, z) : null;

			pixel.write(state, h, this.topH, this.biomeBuffer, blockBiome, light, glowing, cave);

			mapTile.setBlock(x, z, pixel);

			tileChunk.setTile(insideX, insideZ, mapTile, mapWriter.blockStateShortShapeCache);
			mapTile.setWrittenOnce(true);
			mapTile.setLoaded(true);
			XaeroWorldMapCore.chunkCleanField.set(chunk, true);
		}
	}
}