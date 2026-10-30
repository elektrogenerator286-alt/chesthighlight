package dev.example.chesthighlight;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

/**
 * Подсвечивает контейнеры сквозь стены. Клавиша P включает/выключает.
 */
public class ChestHighlightClient implements ClientModInitializer {
	public static final String MOD_ID = "chesthighlight";

	/** Максимальный радиус сканирования в чанках (ограничен дальностью прорисовки). */
	private static final int MAX_SCAN_CHUNKS = 8;
	/** Как часто обновлять список контейнеров (в тиках). */
	private static final int SCAN_INTERVAL_TICKS = 10;
	private static final float ALPHA = 0.40f;
	private static final double PAD = 0.003;

	private static final RenderPipeline FILLED_THROUGH_WALLS = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
					.withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/filled_box_through_walls"))
					.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
					.build());

	private static final ByteBufferBuilder ALLOCATOR = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
	private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
	private static final Vector3f MODEL_OFFSET = new Vector3f();
	private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

	private static BufferBuilder buffer;
	private static MappableRingBuffer vertexBuffer;

	private static volatile boolean enabled = true;
	private static volatile List<Target> targets = List.of();
	private static int tickCounter = 0;

	private static KeyMapping toggleKey;

	private record Target(BlockPos pos, float r, float g, float b) {
	}

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.chesthighlight.toggle",
				InputConstants.Type.KEYSYM,
				InputConstants.KEY_P,
				category));

		ClientTickEvents.END_CLIENT_TICK.register(ChestHighlightClient::onTick);
		WorldRenderEvents.BEFORE_TRANSLUCENT.register(ChestHighlightClient::onRender);
	}

	// ------------------------------------------------------------------ tick / scan

	private static void onTick(Minecraft client) {
		while (toggleKey.consumeClick()) {
			enabled = !enabled;
			if (client.player != null) {
				client.player.displayClientMessage(
						Component.literal("Chest Highlight: " + (enabled ? "ON" : "OFF")), true);
			}
		}

		if (!enabled || client.level == null || client.player == null) {
			targets = List.of();
			return;
		}

		if (++tickCounter < SCAN_INTERVAL_TICKS) {
			return;
		}
		tickCounter = 0;
		targets = scan(client);
	}

	private static List<Target> scan(Minecraft client) {
		ClientLevel level = client.level;
		ChunkPos center = client.player.chunkPosition();
		int radius = Math.min(client.options.renderDistance().get(), MAX_SCAN_CHUNKS);

		List<Target> result = new ArrayList<>();
		for (int cx = center.x - radius; cx <= center.x + radius; cx++) {
			for (int cz = center.z - radius; cz <= center.z + radius; cz++) {
				ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
				if (!(chunk instanceof LevelChunk levelChunk)) {
					continue;
				}
				for (BlockEntity be : levelChunk.getBlockEntities().values()) {
					if (be.isRemoved()) {
						continue;
					}
					Target t = classify(be);
					if (t != null) {
						result.add(t);
					}
				}
			}
		}
		return result;
	}

	private static Target classify(BlockEntity be) {
		BlockPos pos = be.getBlockPos();
		if (be instanceof ChestBlockEntity) {
			// ловушка-сундук — красный, обычный — жёлто-оранжевый
			if (be.getBlockState().is(Blocks.TRAPPED_CHEST)) {
				return new Target(pos, 1.0f, 0.15f, 0.15f);
			}
			return new Target(pos, 1.0f, 0.75f, 0.1f);
		}
		if (be instanceof EnderChestBlockEntity) {
			return new Target(pos, 0.6f, 0.2f, 1.0f);
		}
		if (be instanceof BarrelBlockEntity) {
			return new Target(pos, 0.2f, 0.9f, 0.3f);
		}
		if (be instanceof ShulkerBoxBlockEntity) {
			return new Target(pos, 1.0f, 0.3f, 0.9f);
		}
		return null;
	}

	// ------------------------------------------------------------------ render

	private static void onRender(WorldRenderContext context) {
		List<Target> current = targets;
		if (!enabled || current.isEmpty()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return;
		}

		try {
			extract(context, current);
			draw(client, FILLED_THROUGH_WALLS);
		} finally {
			buffer = null;
		}
	}

	/** Фаза "extraction": пишем вершины в буфер. */
	private static void extract(WorldRenderContext context, List<Target> current) {
		PoseStack matrices = context.matrices();
		Vec3 camera = context.worldState().cameraRenderState.pos;

		matrices.pushPose();
		matrices.translate(-camera.x, -camera.y, -camera.z);

		buffer = new BufferBuilder(ALLOCATOR, FILLED_THROUGH_WALLS.getVertexFormatMode(),
				FILLED_THROUGH_WALLS.getVertexFormat());

		Matrix4fc pose = matrices.last().pose();
		for (Target t : current) {
			BlockPos p = t.pos();
			addBox(pose, buffer,
					(float) (p.getX() - PAD), (float) (p.getY() - PAD), (float) (p.getZ() - PAD),
					(float) (p.getX() + 1 + PAD), (float) (p.getY() + 1 + PAD), (float) (p.getZ() + 1 + PAD),
					t.r(), t.g(), t.b(), ALPHA);
		}

		matrices.popPose();
	}

	private static void addBox(Matrix4fc m, BufferBuilder b,
			float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
			float r, float g, float bl, float a) {
		// Front
		b.addVertex(m, minX, minY, maxZ).setColor(r, g, bl, a);
		b.addVertex(m, maxX, minY, maxZ).setColor(r, g, bl, a);
		b.addVertex(m, maxX, maxY, maxZ).setColor(r, g, bl, a);
		b.addVertex(m, minX, maxY, maxZ).setColor(r, g, bl, a);
		// Back
		b.addVertex(m, maxX, minY, minZ).setColor(r, g, bl, a);
		b.addVertex(m, minX, minY, minZ).setColor(r, g, bl, a);
		b.addVertex(m, minX, maxY, minZ).setColor(r, g, bl, a);
		b.addVertex(m, maxX, maxY, minZ).setColor(r, g, bl, a);
		// Left
		b.addVertex(m, minX, minY, minZ).setColor(r, g, bl, a);
		b.addVertex(m, minX, minY, maxZ).setColor(r, g, bl, a);
		b.addVertex(m, minX, maxY, maxZ).setColor(r, g, bl, a);
		b.addVertex(m, minX, maxY, minZ).setColor(r, g, bl, a);
		// Right
		b.addVertex(m, maxX, minY, maxZ).setColor(r, g, bl, a);
		b.addVertex(m, maxX, minY, minZ).setColor(r, g, bl, a);
		b.addVertex(m, maxX, maxY, minZ).setColor(r, g, bl, a);
		b.addVertex(m, maxX, maxY, maxZ).setColor(r, g, bl, a);
		// Top
		b.addVertex(m, minX, maxY, maxZ).setColor(r, g, bl, a);
		b.addVertex(m, maxX, maxY, maxZ).setColor(r, g, bl, a);
		b.addVertex(m, maxX, maxY, minZ).setColor(r, g, bl, a);
		b.addVertex(m, minX, maxY, minZ).setColor(r, g, bl, a);
		// Bottom
		b.addVertex(m, minX, minY, minZ).setColor(r, g, bl, a);
		b.addVertex(m, maxX, minY, minZ).setColor(r, g, bl, a);
		b.addVertex(m, maxX, minY, maxZ).setColor(r, g, bl, a);
		b.addVertex(m, minX, minY, maxZ).setColor(r, g, bl, a);
	}

	/** Фаза "drawing": заливаем буфер на GPU и рисуем без depth-теста. */
	private static void draw(Minecraft client, RenderPipeline pipeline) {
		MeshData built = buffer.buildOrThrow();
		MeshData.DrawState params = built.drawState();
		VertexFormat format = params.format();

		GpuBuffer vertices = upload(params, format, built);

		GpuBuffer indices;
		VertexFormat.IndexType indexType;
		if (pipeline.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
			built.sortQuads(ALLOCATOR, RenderSystem.getProjectionType().vertexSorting());
			indices = pipeline.getVertexFormat().uploadImmediateIndexBuffer(built.indexBuffer());
			indexType = built.drawState().indexType();
		} else {
			RenderSystem.AutoStorageIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(
					pipeline.getVertexFormatMode());
			indices = shapeIndexBuffer.getBuffer(params.indexCount());
			indexType = shapeIndexBuffer.type();
		}

		GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
				.writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

		try (RenderPass renderPass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(() -> MOD_ID + " through walls",
						client.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(),
						client.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
			renderPass.setPipeline(pipeline);
			RenderSystem.bindDefaultUniforms(renderPass);
			renderPass.setUniform("DynamicTransforms", dynamicTransforms);
			renderPass.setVertexBuffer(0, vertices);
			renderPass.setIndexBuffer(indices, indexType);
			renderPass.drawIndexed(0, 0, params.indexCount(), 1);
		}

		built.close();
		vertexBuffer.rotate();
	}

	private static GpuBuffer upload(MeshData.DrawState params, VertexFormat format, MeshData built) {
		int size = params.vertexCount() * format.getVertexSize();

		if (vertexBuffer == null || vertexBuffer.size() < size) {
			if (vertexBuffer != null) {
				vertexBuffer.close();
			}
			vertexBuffer = new MappableRingBuffer(() -> MOD_ID + " vertex buffer",
					GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, size);
		}

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		try (GpuBuffer.MappedView view = encoder.mapBuffer(
				vertexBuffer.currentBuffer().slice(0, built.vertexBuffer().remaining()), false, true)) {
			MemoryUtil.memCopy(built.vertexBuffer(), view.data());
		}
		return vertexBuffer.currentBuffer();
	}

	/** Вызывается из GameRendererMixin при закрытии рендерера. */
	public static void close() {
		ALLOCATOR.close();
		if (vertexBuffer != null) {
			vertexBuffer.close();
			vertexBuffer = null;
		}
	}
}
