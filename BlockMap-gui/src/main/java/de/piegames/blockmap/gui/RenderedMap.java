package de.piegames.blockmap.gui;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.joml.AABBd;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.DataInput2;
import org.mapdb.DataOutput2;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

public class RenderedMap {

	/** https://github.com/jankotek/mapdb/issues/839 */
	protected Set<Vector3ic>								unloaded			= ConcurrentHashMap.newKeySet();
	public final Serializer<Vector3ic>						VECTOR_SERIALIZER	= new Serializer<Vector3ic>() {

																					@SuppressWarnings("unchecked")
																					@Override
																					public void serialize(DataOutput2 out, Vector3ic value) throws IOException {
																						unloaded.add(value);
																						Serializer.JAVA.serialize(out, value);
																					}

																					@Override
																					public Vector3ic deserialize(DataInput2 input, int available) throws IOException {
																						Vector3ic value = (Vector3ic) Serializer.JAVA.deserialize(input, available);
																						unloaded.remove(value);
																						return value;
																					}

																				};

	/**
	 * Thank the JavaFX guys who a) Made WriteableImage not Serializable b) Didn't include any serialization except by converting to BufferedImage for this ugly
	 * mess.
	 */
	public final Serializer<WritableImage>					IMAGE_SERIALIZER	= new Serializer<WritableImage>() {

																					@Override
																					public boolean isTrusted() {
																								return true;
																					}

																					@Override
																					public void serialize(DataOutput2 out, WritableImage value) throws IOException {
																						byte[] data = new byte[512 * 512 * 4];
																						value.getPixelReader().getPixels(0, 0, 512, 512,
																								PixelFormat.getByteBgraInstance(), data, 0, 512 * 4);
																								out.write(data);
																					}

																					@Override
																					public WritableImage deserialize(DataInput2 input, int available) throws IOException {
																						byte[] data = new byte[available];
																						input.readFully(data);
																						WritableImage ret = new WritableImage(512, 512);
																								ret.getPixelWriter().setPixels(0, 0, 512, 512,
																										PixelFormat.getByteBgraInstance(), data, 0, 512 * 4);
																								return ret;
																							}
																				};

	// Disk for overflow
	private static final DB									cacheDBDisk			= DBMaker.tempFileDB().fileDeleteAfterClose().closeOnJvmShutdown().make();
	// Fast memory cache
	private static final DB									cacheDBMem			= DBMaker.heapDB().closeOnJvmShutdown().make();

	private final HTreeMap<Vector3ic, WritableImage>		cacheMapDisk, cacheMapDiskMem, cacheMapMem;

	private Map<Vector2ic, RenderedRegion>					plainRegions		= new HashMap<>();
	private Map<Integer, Map<Vector2ic, RenderedRegion>>	regions				= new HashMap<>();
	private int														regionsCount, regionsRendered;

	@SuppressWarnings("unchecked")
	public RenderedMap(ScheduledExecutorService executor) {
		cacheMapDisk = cacheDBDisk.hashMap("OnDisk" + System.identityHashCode(this), VECTOR_SERIALIZER, IMAGE_SERIALIZER).create();
		cacheMapDisk.clear();
		cacheMapDiskMem = cacheDBMem
				.hashMap("RenderedRegionCache" + System.identityHashCode(this), Serializer.JAVA, Serializer.JAVA)
				// .expireStoreSize(1)
				.expireMaxSize(1024)
				.expireAfterCreate()
				.expireAfterUpdate()
				// .expireAfterGet()
				.expireAfterCreate(30, TimeUnit.SECONDS)
				.expireAfterUpdate(30, TimeUnit.SECONDS)
				.expireAfterGet(60, TimeUnit.SECONDS)
				.expireOverflow(cacheMapDisk)
				.expireExecutor(executor)
				.expireExecutorPeriod(10000)
				.create();
		// cacheMapMem = cacheMapDiskMem;
		cacheMapMem = cacheDBMem.hashMap("ScaledRegionCache" + System.identityHashCode(this), Serializer.JAVA, Serializer.JAVA)
				// .expireStoreSize(1)
				.expireMaxSize(512)
				.expireAfterCreate()
				.expireAfterUpdate()
				// .expireAfterGet()
				.expireAfterCreate(30, TimeUnit.SECONDS)
				.expireAfterUpdate(30, TimeUnit.SECONDS)
				.expireAfterGet(60, TimeUnit.SECONDS)
				// .expireOverflow(cacheMapDisk)
				.expireExecutor(executor)
				.expireExecutorPeriod(10000)
				.create();
		cacheMapDisk.checkThreadSafe();
		cacheMapDiskMem.checkThreadSafe();
		cacheMapMem.checkThreadSafe();
		clearReload(Collections.emptyList());
	}

	public void close() {
		clearReload(Collections.emptyList());
		cacheMapDiskMem.close();
		cacheMapMem.close();
		cacheMapDisk.close();
		cacheDBMem.close();
		cacheDBDisk.close();
	}

	public void evictCache() {
		cacheMapDiskMem.expireEvict();
		cacheMapMem.expireEvict();
		cacheMapDisk.expireEvict();
	}

	public void clearReload(Collection<Vector2ic> positions) {
		cacheMapDisk.clear();
		cacheMapDiskMem.clear();
		cacheMapMem.clear();
		regions.clear();
		plainRegions.clear();
		regions.put(0, plainRegions);
		positions.stream().map(r -> new RenderedRegion(this, r)).forEach(r -> plainRegions.put(r.position, r));
		regionsRendered = 0;
		regionsCount = positions.size();
	}

	public void invalidateAll() {
		regionsRendered = 0;
		plainRegions.values().forEach(r -> r.invalidateTree(true));
	}

	public boolean isNothingLoaded() {
		return get(0).isEmpty();
	}

	public void draw(GraphicsContext gc, int level, AABBd frustum, double scale) {
		Map<Vector2ic, RenderedRegion> map = get(level > 0 ? 0 : level);
		gc.setFill(new Color(0.3f, 0.3f, 0.9f, 1.0f)); // Background color
		plainRegions.values().stream()
				.filter(r -> r.isVisible(frustum))
				.forEach(r -> r.drawBackground(gc, scale));
		map.entrySet().stream()
				.filter(e -> RenderedRegion.isVisible(e.getKey(), level > 0 ? 0 : level, frustum))
				.map(e -> {
					RenderedRegion r = e.getValue();
					if (e.getValue() == null)
						r = get(level, e.getKey(), true);
					return r;
				})
				.forEach(r -> r.draw(gc, level, frustum, scale));
		plainRegions.values().stream()
				.filter(r -> r.isVisible(frustum))
				.forEach(r -> r.drawForeground(gc, frustum, scale));
	}

	public boolean updateImage(int level, AABBd frustum) {
		try {
			Thread current = Thread.currentThread();
			if (regions.isEmpty())
				// Race hazard: updateImage() is called while clearReload() is reloading all the chunks
				return false;
			return get(level)
					.entrySet()
					.stream()
					.map(e -> e.getValue())
					.filter(r -> r != null)
					.filter(r -> r.isVisible(frustum))
					.filter(r -> current.isInterrupted() ? false : r.updateImage())
					.limit(10)
					.collect(Collectors.toList())
					.size() > 0;
		} catch (ConcurrentModificationException e) {
			// System.out.println(e);
			return true;
		}
	}

	public Map<Vector2ic, RenderedRegion> get(int level) {
		Map<Vector2ic, RenderedRegion> ret = regions.get(level);
		try {
			// the bug might be when requesting values on level 0 that are null (not calculated)
			if (ret == null && level != 0) {
				Map<Vector2ic, RenderedRegion> ret2 = new HashMap<>();
				ret = ret2;
				// the bug might be to using abovePos() regardless of the level being zoomed in or out
				get(level < 0 ? level + 1 : level - 1).keySet().stream().map(RenderedMap::abovePos).map(v -> new Vector2i(v)).distinct().forEach(v -> ret2.put(
						v, null));

				regions.put(level, ret);
			}
		} catch (StackOverflowError e) {
			System.out.println(level + " " + ret);
			throw e;
		}
		return ret;
	}

	public void putImage(Vector2ic pos, WritableImage image) {
		if (!plainRegions.containsKey(pos))
			throw new IllegalArgumentException("Position out of bounds");
		plainRegions.get(pos).setImage(image);
	}

	public void updateCounter(RenderedRegion r) {
		if (get(0).containsValue(r))
			regionsRendered++;
		else
			System.out.println("[RenderedMap] NOT FOUND"); // TODO what is this
	}

	public float getProgress() {
		if (isNothingLoaded())
			return 1;
		return (float) regionsRendered / regionsCount;
	}

	public RenderedImage createImage(RenderedRegion r) {
		return new RenderedImage(this, r.level <= 0 ? cacheMapDiskMem : cacheMapMem, new Vector3i(r.position.x(), r.position.y(), r.level));
	}

	protected boolean isImageLoaded(Vector3ic key) {
		return !unloaded.contains(key);
	}

	public RenderedRegion get(int level, Vector2ic position, boolean create) {
		Map<Vector2ic, RenderedRegion> map = get(level);
		RenderedRegion r = map.get(new Vector2i(position));
		if (create && r == null && level != 0 && ((level > 0 /* && plainRegions.containsKey(new Vector2i(position.x() >> level, position.y() >>
																 * level).toImmutable()) */) || map.containsKey(position))) {
			r = new RenderedRegion(this, level, position);
			if (level < 0)
				Arrays.stream(belowPos(position)).forEach(pos -> get(level + 1, position, true));
			if (level > 0)
				get(level - 1, abovePos(position), true);
			map.put(position, r);
		}
		return r;
	}

	public RenderedRegion[] get(int level, Vector2ic[] belowPos, boolean create) {
		RenderedRegion[] ret = new RenderedRegion[belowPos.length];
		for (int i = 0; i < belowPos.length; i++)
			ret[i] = get(level, belowPos[i], create);
		return ret;
	}

	public static Vector2ic abovePos(Vector2ic pos) {
		return groundPos(pos, 1);
	}

	public static Vector2ic groundPos(Vector2ic pos, int levelDiff) {
		return new Vector2i(pos.x() >> levelDiff, pos.y() >> levelDiff);
	}

	public static Vector2ic[] belowPos(Vector2ic pos) {
		Vector2ic belowPos = new Vector2i(pos.x() << 1, pos.y() << 1);
		return new Vector2ic[] {
				new Vector2i(0, 0).add(belowPos),
				new Vector2i(1, 0).add(belowPos),
				new Vector2i(0, 1).add(belowPos),
				new Vector2i(1, 1).add(belowPos)
		};
	}

	public static WritableImage halfSize(WritableImage old, WritableImage topLeft, WritableImage topRight, WritableImage bottomLeft, WritableImage bottomRight) {
		WritableImage output = old != null ? old : new WritableImage(512, 512);

		PixelReader topLeftReader = topLeft != null ? topLeft.getPixelReader() : null;
		PixelReader topRightReader = topRight != null ? topRight.getPixelReader() : null;
		PixelReader bottomLeftReader = bottomLeft != null ? bottomLeft.getPixelReader() : null;
		PixelReader bottomRightReader = bottomRight != null ? bottomRight.getPixelReader() : null;

		int[] topLeftPixels = genBuffer(512 * 512);
		int[] topRightPixels = genBuffer(512 * 512);
		int[] bottomLeftPixels = genBuffer(512 * 512);
		int[] bottomRightPixels = genBuffer(512 * 512);

		if (topLeftReader != null)
			topLeftReader.getPixels(0, 0, 512, 512, PixelFormat.getIntArgbInstance(), topLeftPixels, 0, 512);
		if (topRightReader != null)
			topRightReader.getPixels(0, 0, 512, 512, PixelFormat.getIntArgbInstance(), topRightPixels, 0, 512);
		if (bottomLeftReader != null)
			bottomLeftReader.getPixels(0, 0, 512, 512, PixelFormat.getIntArgbInstance(), bottomLeftPixels, 0, 512);
		if (bottomRightReader != null)
			bottomRightReader.getPixels(0, 0, 512, 512, PixelFormat.getIntArgbInstance(), bottomRightPixels, 0, 512);

		PixelWriter writer = output.getPixelWriter();

		// TODO optimize with buffers
		for (int y = 0; y < 256; y++) {
			for (int x = 0; x < 256; x++) {
				int rx = x * 2;
				int ry = y * 2;
				writer.setArgb(x, y, topLeftReader != null ? sampleColor(rx, ry, topLeftPixels) : 0);
				writer.setArgb(x + 256, y, topRightReader != null ? sampleColor(rx, ry, topRightPixels) : 0);
				writer.setArgb(x, y + 256, bottomLeftReader != null ? sampleColor(rx, ry, bottomLeftPixels) : 0);
				writer.setArgb(x + 256, y + 256, bottomRightReader != null ? sampleColor(rx, ry, bottomRightPixels) : 0);
			}
		}
		return output;
	}

	private static int sampleColor(int x, int y, int[] colors) {
		// Image image = new Image(is, requestedWidth, requestedHeight, preserveRatio, smooth)
		// int[] colors = genBuffer(4);
		// reader.getPixels(x, y, 2, 2, WritablePixelFormat.getIntArgbInstance(), colors, 0, 2);

		int c1 = colors[y * 512 + x], c2 = colors[y * 512 + x + 1], c3 = colors[y * 512 + x + 512], c4 = colors[y * 512 + x + 513];
		// TODO premultiply alpha to avoid dark edges
		long ret = 0;// use long against overflow
		long a1 = c1 >>> 24, a2 = c2 >>> 24, a3 = c3 >>> 24, a4 = c4 >>> 24;
		// alpha
		ret |= ((a1 + a2 + a3 + a4) << 22) & 0xFF000000;
		// red
		ret |= ((((c1 & 0x00FF0000) * a1 + (c2 & 0x00FF0000) * a2 + (c3 & 0x00FF0000) * a3 + (c4 & 0x00FF0000) * a4) / 255) >> 2) & 0x00FF0000;
		// green
		ret |= ((((c1 & 0x0000FF00) * a1 + (c2 & 0x0000FF00) * a2 + (c3 & 0x0000FF00) * a3 + (c4 & 0x0000FF00) * a4) / 255) >> 2) & 0x0000FF00;
		// blue
		ret |= ((((c1 & 0x000000FF) * a1 + (c2 & 0x000000FF) * a2 + (c3 & 0x000000FF) * a3 + (c4 & 0x000000FF) * a4) / 255) >> 2) & 0x000000FF;
		return (int) ret;
	}

	public static WritableImage doubleSize(WritableImage old, WritableImage input, int levelDiff, Vector2i subTile) {
		WritableImage output = old != null ? old : new WritableImage(512, 512);

		PixelReader reader = input.getPixelReader();
		PixelWriter writer = output.getPixelWriter();

		int tileSize = 512 >> levelDiff;
		int scaleFactor = 1 << levelDiff;

		int[] pixels = genBuffer(tileSize * tileSize);
		int[] pixel = genBuffer(scaleFactor * scaleFactor);
		reader.getPixels(subTile.x * tileSize, subTile.y * tileSize, tileSize, tileSize, PixelFormat.getIntArgbInstance(), pixels, 0, tileSize);

		for (int y = 0; y < tileSize; y++) {
			for (int x = 0; x < tileSize; x++) {
				final int argb = pixels[y * tileSize + x];
				Arrays.fill(pixel, argb);
				writer.setPixels(x * scaleFactor, y * scaleFactor, scaleFactor, scaleFactor, PixelFormat.getIntArgbInstance(), pixel, 0, scaleFactor);
			}
		}
		return output;
	}

	/** Test how much time this takes to see if object pooling is needed. */
	private static int[] genBuffer(int length) {
		return new int[length];
	}
}