/*
 * Copyright 2017 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.world.zones;

import org.terasology.math.geom.BaseVector3i;
import org.terasology.math.geom.ImmutableVector3i;
import org.terasology.module.sandbox.API;
import org.terasology.rendering.nui.layers.mainMenu.preview.FacetLayerPreview;
import org.terasology.rendering.nui.layers.mainMenu.preview.PreviewGenerator;
import org.terasology.world.block.Block;
import org.terasology.world.chunks.CoreChunk;
import org.terasology.world.generation.EntityBuffer;
import org.terasology.world.generation.EntityProvider;
import org.terasology.world.generation.FacetProvider;
import org.terasology.world.generation.Region;
import org.terasology.world.generation.WorldBuilder;
import org.terasology.world.generation.WorldRasterizer;
import org.terasology.world.generator.WorldGenerator;
import org.terasology.world.viewer.layers.FacetLayer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.terasology.world.chunks.ChunkConstants.SIZE_X;
import static org.terasology.world.chunks.ChunkConstants.SIZE_Y;
import static org.terasology.world.chunks.ChunkConstants.SIZE_Z;

/**
 * A region in the world with its own rasterization and world preview properties.
 *
 * Each top-level zone (those directly added to the {@link WorldBuilder}) with one or more preview layers added to it
 * will show up in the drop-down list of zones on the world preview screen, allowing it to be previewed separately.
 *
 * A zone affects a certain region, given by the {@link #regionFunction}. Only blocks that are present in this region
 * will be rasterized, so zones can be combined together and, unless their regions overlap, they will not be able to
 * affect each other.
 *
 * Zones can also be nested, with the inner zones only being able to affect areas that are also included in their parent
 * zone. Preview layers on the inner zones also get added to their parent zone.
 */
@API
public class Zone extends ProviderStore implements WorldRasterizer, EntityProvider {

    private final List<FacetProvider> facetProviders = new ArrayList<>();
    private final List<WorldRasterizer> rasterizers = new ArrayList<>();
    private final List<EntityProvider> entityProviders = new ArrayList<>();
    private final List<FacetLayer> facetLayers = new ArrayList<>();

    /**
     * This zone's parent. This is either the base {@link WorldBuilder}, or another zone.
     */
    private ProviderStore parent;

    private final ZoneRegionFunction regionFunction;
    private final String name;


    public Zone(String name, BooleanSupplier regionFunction) {
        this(name, (pos, region) -> regionFunction.getAsBoolean());
    }

    public Zone(String name, Predicate<BaseVector3i> regionFunction) {
        this(name, (pos, region) -> regionFunction.test(pos));
    }

    public Zone(String name, BiPredicate<BaseVector3i, Region> regionFunction) {
        this(name, (x, y, z, region) -> regionFunction.test(new ImmutableVector3i(x, y, z), region));
    }

    /**
     * @param name the name of this zone
     * @param regionFunction the region function to use
     */
    public Zone(String name, ZoneRegionFunction regionFunction) {
        this.regionFunction = regionFunction;
        this.name = name;
    }


    /* WorldRasterizer methods */


    @Override
    public void initialize() {
        //TODO: this gets called twice (WorldRasterizer and EntityProvider)
        regionFunction.initialize(this);
        rasterizers.forEach(WorldRasterizer::initialize);
    }

    /**
     * Process the EntityProviders.
     *
     * @see EntityProvider#process(Region, EntityBuffer)
     */
    @Override
    public void process(Region region, EntityBuffer buffer) {
        entityProviders.forEach(EntityProvider::initialize);
        entityProviders.forEach(provider -> provider.process(region, buffer));
    }

    /**
     * Generate the chunk for this zone, based on the rasterizers and nested zones that have been added.
     *
     * This will only change blocks for which {@link #containsBlock(int, int, int, Region)} returns true.
     *
     * @see WorldRasterizer#generateChunk(CoreChunk, Region)
     */
    @Override
    public void generateChunk(CoreChunk chunk, Region chunkRegion) {
        Block[][][] savedBlocks = new Block[SIZE_X][SIZE_Y][SIZE_Z];
        boolean changeAllBlocks = true;
        boolean saveAllBlocks = true;

        int offsetX = chunk.getChunkWorldOffsetX();
        int offsetY = chunk.getChunkWorldOffsetY();
        int offsetZ = chunk.getChunkWorldOffsetZ();

        //Save the blocks that aren't in the zone
        for (int x = 0; x < SIZE_X; x++) {
            for (int y = 0; y < SIZE_Y; y++) {
                for (int z = 0; z < SIZE_Z; z++) {
                    if (!containsBlock(x + offsetX, y + offsetY, z + offsetZ, chunkRegion)) {
                        savedBlocks[x][y][z] = chunk.getBlock(x, y, z);
                        changeAllBlocks = false;
                    } else {
                        saveAllBlocks = false;
                    }
                }
            }
        }

        //If none of the blocks are in the zone, it doesn't need to be rasterized
        if (!saveAllBlocks) {
            //Rasterize the zone
            rasterizers.forEach(r -> r.generateChunk(chunk, chunkRegion));

            //Replace any blocks that aren't in the zone
            if (!changeAllBlocks) {
                for (int x = 0; x < SIZE_X; x++) {
                    for (int y = 0; y < SIZE_Y; y++) {
                        for (int z = 0; z < SIZE_Z; z++) {
                            Block block = savedBlocks[x][y][z];
                            if (block != null) {
                                chunk.setBlock(x, y, z, block);
                            }
                        }
                    }
                }
            }
        }
    }


    /* Preview features */


    /**
     * Add a preview layer to this zone, which will be used when the preview is generated.
     *
     *
     * @param facetLayer
     * @return
     */
    public Zone addPreviewLayer(FacetLayer facetLayer) {
        facetLayers.add(facetLayer);
        return this;
    }

    /**
     * @return the list of added preview layers
     */
    public List<FacetLayer> getPreviewLayers() {
        return facetLayers;
    }

    /**
     * Generate a preview screen for this zone, using the added preview layers.
     *
     * @param generator the world generator that is in use
     * @return a PreviewGenerator for this zone
     */
    public PreviewGenerator preview(WorldGenerator generator) {
        return new FacetLayerPreview(generator, facetLayers);
    }


    /* General utility */


    /**
     * Test whether this zone contains the block at the given world position.
     *
     * @param x the world x position of the block
     * @param y the world y position of the block
     * @param z the world z position of the block
     * @param chunkRegion the Region corresponding to that position
     * @return true if the zone contains the given block; false otherwise
     */
    public boolean containsBlock(int x, int y, int z, Region chunkRegion) {
        return regionFunction.apply(x, y, z, chunkRegion);
    }

    /**
     * @return the region function that this zone is using
     */
    public ZoneRegionFunction getRegionFunction() {
        return regionFunction;
    }

    /**
     * @return the list of facet providers attached to this zone
     */
    public List<FacetProvider> getFacetProviders() {
        return facetProviders;
    }

    /**
     * @return the list of rasterizers attached to this zone
     */
    public List<WorldRasterizer> getRasterizers() {
        return rasterizers;
    }

    /**
     * Set the parent of this zone.
     *
     * @see #getParent()
     *
     * @param parent the parent of this zone
     */
    public void setParent(ProviderStore parent) {
        this.parent = parent;
    }

    /**
     * The parent is the ProviderStore that this zone is attached to (via the {@link ProviderStore#addZone(Zone)}
     * method)
     *
     * @return the parent of this zone
     */
    public ProviderStore getParent() {
        return parent;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return getName();
    }

    /**
     * Get the region functions of all zones which are siblings of the given zone (including the given zone).
     *
     * Sibling zones are zones that have the same parent.
     *
     * @param zone the zone to find the siblings of
     * @return the region functions of the given zone's siblings
     */
    static List<ZoneRegionFunction> getSiblingRegionFunctions(Zone zone) {
        return zone.getParent().getChildZones().stream()
                .map(Zone::getRegionFunction)
                .collect(Collectors.toList());
    }


    /* BuilderZone methods */


    @Override
    public Zone addProvider(FacetProvider facet) {
        facetProviders.add(facet);
        return this;
    }

    @Override
    public Zone addEntities(EntityProvider entityProvider) {
        entityProviders.add(entityProvider);
        return this;
    }

    @Override
    public Zone addRasterizer(WorldRasterizer rasterizer) {
        rasterizers.add(rasterizer);
        return this;
    }

    @Override
    public Zone addZone(Zone zone) {
        super.addZone(zone);
        zone.getPreviewLayers().forEach(this::addPreviewLayer);
        return this;
    }

}
