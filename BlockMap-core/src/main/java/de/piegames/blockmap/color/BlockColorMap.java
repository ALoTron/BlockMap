package de.piegames.blockmap.color;

import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;

import de.piegames.blockmap.renderer.Block;
import io.gsonfire.GsonFireBuilder;
import io.gsonfire.annotations.PostDeserialize;
import io.gsonfire.annotations.PreSerialize;

/**
 * Represents a mapping from block states to properties that are used to calculate this block's color. These properties are: base color, if
 * the block is a grass/foliage/water block and if the block is translucent. The base color usually is the average color of its texture, but
 * this is not a requirement. If the block is a grass/foliage/water block, its color will be multiplied with the respective colors of the
 * biome the block is in. The grass/foliage/water properties are independent from each other, a block may as well be both (even if the
 * result may not look that well).
 * 
 * The translucency property has no correlation with the transparency of that block (which is encoded in its base color) nor with any
 * properties directly related to Minecraft internals. See {@link #isTranslucentBlock(Block)} for more details.
 * 
 * @author piegames
 */
public class BlockColorMap {

	public static enum InternalColorMap {
		DEFAULT("default"), NO_FOLIAGE("foliage"), OCEAN_GROUND("water"), CAVES("caves");
		private String fileName;

		InternalColorMap(String fileName) {
			this.fileName = Objects.requireNonNull(fileName);
		}

		public BlockColorMap getColorMap() {
			return BlockColorMap.loadInternal(fileName);
		}
	}

	public static final Gson		GSON	= new GsonFireBuilder()
			.enableHooks(BlockColorMap.class)
			.createGsonBuilder()
			.serializeNulls()
			.addSerializationExclusionStrategy(new ExclusionStrategy() {

														@Override
														public boolean shouldSkipField(FieldAttributes f) {
															return f.hasModifier(Modifier.TRANSIENT);
														}

														@Override
														public boolean shouldSkipClass(Class<?> clazz) {
															return false;
														}
													})
			.registerTypeAdapter(Color.class, Color.ADAPTER)
			.disableHtmlEscaping()
			.create();
	public static final BlockColor	MISSING	= new BlockColor(Color.MISSING, false, false, false, false);

	public static final class BlockColor {

		public Color	color;
		/** Tell if a given block has a grassy surface which should be tainted according to the biome the block stands in */
		public boolean	isGrass;
		/** Tell if a given block represents foliage and should be tainted according to the biome the block stands in */
		public boolean	isFoliage;
		/** Tell if a given block contains water and should be tainted according to the biome the block stands in */
		public boolean	isWater;
		/** Tell if a given block is letting light through and thus will not count to any height shading calculations */
		public boolean	isTranslucent;

		public BlockColor() {

		}

		public BlockColor(Color color, boolean isGrass, boolean isFoliage, boolean isWater, boolean isTranslucent) {
			this.color = color;
			this.isGrass = isGrass;
			this.isFoliage = isFoliage;
			this.isWater = isWater;
			this.isTranslucent = isTranslucent;
		}

		@Override
		public String toString() {
			return "BlockColor [color=" + color + ", isGrass=" + isGrass + ", isFoliage=" + isFoliage + ", isWater=" + isWater + ", isTranslucent="
					+ isTranslucent + "]";
		}
	}

	protected transient Map<Block, BlockColor>	blockColors;
	/*
	 * Use this map instead of blockColors for serialization, because the keys are primitive (String). A serialization hook will convert between
	 * the two map forms if needed.
	 */
	protected Map<String, BlockColor>			blockSerialize;
	protected transient Color					airColor;

	@SuppressWarnings("unused")
	private BlockColorMap() {
		// For deserialization purposes
	}

	public BlockColorMap(Map<Block, BlockColor> blockColors) {
		this.blockColors = Objects.requireNonNull(blockColors);
	}

	@Deprecated
	public BlockColorMap(Map<Block, Color> blockColors, Set<Block> grassBlocks, Set<Block> foliageBlocks, Set<Block> waterBlocks,
			Set<Block> translucentBlocks) {
		this.blockColors = new HashMap<>();
		blockColors.forEach((b, c) -> {
			this.blockColors.put(b, new BlockColor(c,
					grassBlocks.contains(b),
					foliageBlocks.contains(b),
					waterBlocks.contains(b),
					translucentBlocks.contains(b)));
		});
	}

	@PreSerialize
	public void preSerializeLogic() {
		blockSerialize = blockColors.entrySet().stream().collect(Collectors.toMap(e -> e.getKey().toString(), e -> e.getValue()));
	}

	@PostDeserialize
	public void postDeserializeLogic() {
		blockColors = blockSerialize.entrySet().stream().collect(Collectors.toMap(e -> Block.byCompactForm(e.getKey()).get(0), e -> e.getValue()));
		blockSerialize = null;
	}

	public BlockColor getBlockColor(Block block) {
		return blockColors.getOrDefault(block, MISSING);
	}

	/** This is a common operation so avoid retrieving it from the map every time. */
	public Color getAirColor() {
		if (airColor == null)
			return airColor = getBlockColor(Block.AIR).color;
		else
			return airColor;
	}

	public boolean hasBlockColor(Block block) {
		return blockColors.containsKey(block);
	}

	public static BlockColorMap load(Reader reader) {
		return GSON.fromJson(reader, BlockColorMap.class);
	}

	public static BlockColorMap loadDefault() {
		return loadInternal("default");
	}

	public static BlockColorMap loadInternal(String name) {
		try {
			return load(new InputStreamReader(BlockColorMap.class.getResourceAsStream("/block-colors-" + name + ".json")));
		} catch (NullPointerException e) {
			throw new IllegalArgumentException("Did not find internal color map " + name, e);
		}
	}
}
