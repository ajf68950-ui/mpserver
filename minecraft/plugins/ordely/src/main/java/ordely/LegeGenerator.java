package ordely;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.List;
import java.util.Random;

/** Lege wereld (alleen lucht); de gevangenis wordt er door Bouwer in gezet. */
final class LegeGenerator extends ChunkGenerator {
    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    @Override public boolean shouldGenerateCaves() { return false; }
    @Override public boolean shouldGenerateDecorations() { return false; }
    @Override public boolean shouldGenerateMobs() { return false; }
    @Override public boolean shouldGenerateStructures() { return false; }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) { return Bouwer.bewaking(world); }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new BiomeProvider() {
            @Override public Biome getBiome(WorldInfo info, int x, int y, int z) { return Biome.PLAINS; }
            @Override public List<Biome> getBiomes(WorldInfo info) { return List.of(Biome.PLAINS); }
        };
    }
}
