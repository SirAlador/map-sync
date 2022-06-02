package gjum.minecraft.mapsync.common.integration;

import gjum.minecraft.mapsync.common.data.BlockColumn;
import gjum.minecraft.mapsync.common.data.BlockInfo;
import gjum.minecraft.mapsync.common.data.ChunkTile;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import xaero.map.biome.BiomeKey;
import xaero.map.biome.BiomeKeyManager;
import xaero.map.cache.BlockStateShortShapeCache;
import xaero.map.core.XaeroWorldMapCore;
import xaero.map.region.MapBlock;
import xaero.map.region.MapTile;
import xaero.map.region.MapTileChunk;
import xaero.map.server.core.XaeroWorldMapServerCore;

import java.util.Optional;
import java.util.concurrent.Semaphore;

public class XaerosMapHelperReal {
	//Xaero literally put a star bastion with anti-aircraft guns around the stupid BiomeKeyManager instance, so I guess I'll just make my own
	public static final Object biome_keys_sync = new Object();
	public static final BiomeKeyManager biome_keys = new BiomeKeyManager();


	public static boolean is_mapping() {
		if (XaeroWorldMapCore.currentSession == null) return false;
		return XaeroWorldMapCore.currentSession.isUsable() && XaeroWorldMapCore.currentSession.getMapProcessor().getWorld() != null;
	}

	public static boolean update_with_chunk_tile(ChunkTile source) {
		if (Minecraft.getInstance().isSameThread()) {
			actual_update_with_chunk(source);
		} else {
			//Xaeros stuff needs to be run on the minecraft thread
			//Block the current thread while it runs so the main thread isn't flooded
			Semaphore block = new Semaphore(1);
			block.tryAcquire();
			Exception[] exception = new Exception[1];
			Minecraft.getInstance().execute(() -> {
				try { actual_update_with_chunk(source); }
				catch (Exception e) { exception[0] = e; }
				finally { block.release(); }
			});
			try { block.acquire(); }
			catch (InterruptedException e) {}
			if (exception[0] != null) {
				if (exception[0] instanceof  RuntimeException) throw (RuntimeException)exception[0];
				else throw new RuntimeException(exception[0]);
			}
		}

		//Why???
		return true;
	}

	private static void actual_update_with_chunk(ChunkTile source) {
		/*
			MapProcessor holds MapRegions
			Each MapRegion holds 8x8 MapTileChunks
			Each MapTileChunk holds 4x4 Map Tiles
			Each MapTiles holds 16x16 blocks
		*/

		int xaeros_tile_x = source.x();
		int xaeros_tile_z = source.z();
		//Divide by 32 to get the region
		int xaeros_region_x = xaeros_tile_x >> 5;
		int xaeros_region_z = xaeros_tile_z >> 5;
		// - Adjust to be within region-local coordinates
		xaeros_tile_x -= (xaeros_region_x * 32);
		xaeros_tile_z -= (xaeros_region_z * 32);
		// - Divide by 4 to get the subregion
		// - Only select the first 3 bits to remove the region identifier
		int xaeros_subregion_x = xaeros_tile_x >> 2;
		int xaeros_subregion_z = xaeros_tile_z >> 2;
		//The last bits are the location within the subregion
		xaeros_tile_x &= 0b11;
		xaeros_tile_z &= 0b11;
		System.out.printf("Rendering Xaeros Chunk [%d %d] -> [%d %d] [%d %d] [%d %d]\n", source.x(), source.z(), xaeros_region_x, xaeros_region_z, xaeros_subregion_x, xaeros_subregion_z, xaeros_tile_x, xaeros_tile_z);

		var mapProcessor = XaeroWorldMapCore.currentSession.getMapProcessor();

		//Create doesn't guarentee creation of a new region, just specifies that it's an option.
		//Evidently creation is an expensive operation or smth
		var region = mapProcessor.getMapRegion(xaeros_region_x, xaeros_region_z, true);


		while (!region.isLoaded()) {
			mapProcessor.getMapSaveLoad().requestLoad(region, "mapsync");
			try { Thread.sleep(1000); }
			catch (InterruptedException e) { return; }
		}

		//Ensure nothing else makes map changes while this happens
		synchronized (region.writerThreadPauseSync) {
			//Get the subregion from the region
			var subregion = region.getChunk(xaeros_subregion_x, xaeros_subregion_z);
			if (subregion == null) {
				//Create a new map tile for the chunk
				subregion = new MapTileChunk(region, source.x(), source.z());
				region.setChunk(xaeros_subregion_x, xaeros_subregion_z, subregion);
				//TODO figure out what this does
				subregion.setLoadState((byte) 2);
			}

			//Don't get a map tile, as setTile does a bunch of funky stuff. Make a new MapTile and override any existing one
			//InteliJ says this takes parameters of `Object[] ...args` sooooo I hpoe this works
			MapTile dest = new MapTile(null, source.x(), source.z());

			//Copy data
			for (int x = 0; x < 16; x++) {
				for (int z = 0; z < 16; z++) {

					BlockColumn block = source.getBlockColumn(x, z);

					BiomeKey biome;
					synchronized (biome_keys_sync) {
						String biome_identifier = mapProcessor.worldBiomeRegistry.getKey(block.biome()).toString();
						//
						biome = biome_keys.get(biome_identifier);
					}
					byte light = (byte) block.light();


					Optional<BlockInfo> highest = block.layers().stream().max((a, b) -> Integer.compare(a.y(), b.y()));
					if (highest.isPresent()) {

						var pixel = dest.getBlock(x, z);

						if (pixel == null) pixel = new MapBlock();
						pixel.write(highest.get().state(), highest.get().y(), highest.get().y(), biome, light, false, false);

						dest.setBlock(x, z, pixel);

					}
					//else { uh oh }
				}
			}


			dest.setWrittenOnce(true);
			dest.setLoaded(true);
			MapTile prev = subregion.getTile(xaeros_tile_x, xaeros_tile_z);
			if (prev != null) {
				//mapProcessor.getTilePool()
			}
			subregion.setTile(xaeros_tile_x, xaeros_tile_z, dest, mapProcessor.getBlockStateShortShapeCache());
			subregion.setChanged(true);
			//IDK what this does but I guess it works without it
			region.requestRefresh(mapProcessor);
			mapProcessor.getTilePool().addToPool(dest);


			//XaeroWorldMapCore.chunkCleanField.set(dest, true);
		}
	}
}