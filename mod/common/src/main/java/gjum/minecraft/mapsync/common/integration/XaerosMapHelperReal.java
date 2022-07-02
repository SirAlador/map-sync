package gjum.minecraft.mapsync.common.integration;

import gjum.minecraft.mapsync.common.data.BlockColumn;
import gjum.minecraft.mapsync.common.data.BlockInfo;
import gjum.minecraft.mapsync.common.data.ChunkTile;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import xaero.map.MapProcessor;
import xaero.map.biome.BiomeKey;
import xaero.map.biome.BiomeKeyManager;
import xaero.map.cache.BlockStateShortShapeCache;
import xaero.map.core.XaeroWorldMapCore;
import xaero.map.region.MapBlock;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTile;
import xaero.map.region.MapTileChunk;

import java.util.Optional;
import java.util.concurrent.Semaphore;

public class XaerosMapHelperReal {
	//Xaero literally put a star bastion with anti-aircraft guns around the stupid BiomeKeyManager instance, so I guess I'll just make my own
	public static final Object biome_keys_sync = new Object();
	public static final BiomeKeyManager biome_keys = new BiomeKeyManager();


	public static boolean is_mapping() {
		boolean is_mapping = XaeroWorldMapCore.currentSession != null
				&& XaeroWorldMapCore.currentSession.isUsable()
				&& XaeroWorldMapCore.currentSession.getMapProcessor().getWorld() != null
				&& XaeroWorldMapCore.currentSession.getMapProcessor().getWorld().dimensionType().bedWorks(); //Overworld, lol
		//System.out.println("Xaeros IS MAPPING??? " + is_mapping);
		return is_mapping;
	}

	private static Semaphore on_minecraft_thread_semaphore = new Semaphore(1, true);
	//Xaeros stuff needs to be run on the minecraft thread
	//Block the current thread while it runs so the main thread isn't flooded
	public static void block_with_mc_thread(Runnable task) {
		if (Minecraft.getInstance().isSameThread()) {
			task.run();
		} else {
			try { on_minecraft_thread_semaphore.acquire(); }
			catch (InterruptedException e) { throw new RuntimeException(e); }
			Exception[] exception = new Exception[1];
			Minecraft.getInstance().execute(() -> {
				try { task.run(); }
				catch (Exception e) { exception[0] = e; }
				finally { on_minecraft_thread_semaphore.release(); }
			});
		}
	}

	public static boolean update_with_chunk(ChunkTile source) throws InterruptedException {
		if (!is_mapping()) return false;

		/*
			MapProcessor holds MapRegions
			Each MapRegion holds 8x8 MapTileChunks
			Each MapTileChunk holds 4x4 Map Tiles
			Each MapTiles holds 16x16 blocks
		*/

		int _xaeros_tile_x = source.x();
		int _xaeros_tile_z = source.z();
		//Divide by 32 to get the region
		final int xaeros_region_x = _xaeros_tile_x >> 5;
		final int xaeros_region_z = _xaeros_tile_z >> 5;
		// - Adjust to be within region-local coordinates
		_xaeros_tile_x -= (xaeros_region_x * 32);
		_xaeros_tile_z -= (xaeros_region_z * 32);
		// - Divide by 4 to get the subregion
		// - Only select the first 3 bits to remove the region identifier
		final int xaeros_subregion_x = _xaeros_tile_x >> 2;
		final int xaeros_subregion_z = _xaeros_tile_z >> 2;
		//The last bits are the location within the subregion
		_xaeros_tile_x &= 0b11;
		_xaeros_tile_z &= 0b11;
		final int xaeros_tile_x = _xaeros_tile_x;
		final int xaeros_tile_z = _xaeros_tile_z;
		System.out.printf("Rendering Xaeros Chunk [%d %d] -> [%d %d] [%d %d] [%d %d]\n", source.x(), source.z(), xaeros_region_x, xaeros_region_z, xaeros_subregion_x, xaeros_subregion_z, xaeros_tile_x, xaeros_tile_z);


		var mapProcessor = XaeroWorldMapCore.currentSession.getMapProcessor();

		//Create doesn't guarentee creation of a new region, just specifies that it's an option.
		//Evidently creation is an expensive operation or smth
		MapRegion[] region = new MapRegion[1];
		while (region[0] == null) {
			block_with_mc_thread(() -> {
				region[0] = mapProcessor.getMapRegion(xaeros_region_x, xaeros_region_z, true);
				if (region[0] != null) region[0].setBeingWritten(true);
			});
			if (region[0] == null) Thread.sleep(1000);
		}
		while (!region[0].isLoaded()) {
			block_with_mc_thread(() -> {
				mapProcessor.getMapSaveLoad().requestLoad(region[0], "mapsync");
			});
			if (!region[0].isLoaded()) Thread.sleep(1000);
		}
		region[0].setHasHadTerrain();

		boolean[] success = new boolean[] { false };
		MapTile[] to_enqueue = new MapTile[1];


		block_with_mc_thread(() -> {
			//Ensure nothing else makes map changes while this happens
			synchronized (region[0].writerThreadPauseSync) {
				//Get the subregion from the region
				var subregion = region[0].getChunk(xaeros_subregion_x, xaeros_subregion_z);
				if (subregion == null) {
					//Create a new map tile for the chunk
					//X and Z are in units of MapTileChunk
					subregion = new MapTileChunk(region[0], xaeros_region_x * 8 + xaeros_subregion_x, xaeros_region_z * 8 + xaeros_subregion_z);
					//Mark the region as loaded
					subregion.setLoadState((byte) 2);
					region[0].setChunk(xaeros_subregion_x, xaeros_subregion_z, subregion);
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
				subregion.setHasHighlights(false);
				//IDK what this does but I guess it works without it
				region[0].setBeingWritten(false);
				region[0].setShouldCache(true, "mapsync");
				region[0].requestRefresh(mapProcessor, false);

				success[0] = true;
				to_enqueue[0] = dest;
			}
		});

		if (success[0]) {
			for (MapTile tile : to_enqueue) {
				while (!mapProcessor.getTilePool().addToPool(tile)) {
					try { Thread.sleep(1000); }
					catch (InterruptedException e) { throw new RuntimeException(e); }
				}
			}
		}

		return true;
	}
}