package com.autodig.client;

import com.autodig.AutoDig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public class AutoDigClient implements ClientModInitializer {
	private static BlockPos pos1;
	private static BlockPos pos2;

	private static KeyBinding keyPos1;
	private static KeyBinding keyPos2;
	private static KeyBinding keyToggle;

	@Override
	public void onInitializeClient() {
		keyPos1 = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.autodig.pos1", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "category.autodig"));
		keyPos2 = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.autodig.pos2", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, "category.autodig"));
		keyToggle = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.autodig.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, "category.autodig"));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null) return;
			while (keyPos1.wasPressed()) mark(client, 1);
			while (keyPos2.wasPressed()) mark(client, 2);
			while (keyToggle.wasPressed()) toggle(client);
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			pos1 = null;
			pos2 = null;
		});

		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(AutoDigClient::render);
	}

	private static void mark(MinecraftClient client, int which) {
		if (client.player.isSneaking()) {
			pos1 = null;
			pos2 = null;
			client.player.sendMessage(Text.literal("Выделение сброшено").formatted(Formatting.YELLOW), true);
			return;
		}
		HitResult hit = client.player.raycast(64, 0, false);
		BlockPos pos = hit.getType() == HitResult.Type.BLOCK ? ((BlockHitResult) hit).getBlockPos() : client.player.getBlockPos();
		if (which == 1) pos1 = pos; else pos2 = pos;

		String text = "Точка " + which + ": " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
		if (pos1 != null && pos2 != null) {
			long volume = (long) (Math.abs(pos1.getX() - pos2.getX()) + 1)
					* (Math.abs(pos1.getY() - pos2.getY()) + 1)
					* (Math.abs(pos1.getZ() - pos2.getZ()) + 1);
			text += "  | объём: " + volume;
		}
		client.player.sendMessage(Text.literal(text).formatted(Formatting.GREEN), true);
	}

	private static void toggle(MinecraftClient client) {
		if (!ClientPlayNetworking.canSend(AutoDig.TOGGLE)) {
			client.player.sendMessage(Text.literal("AutoDig не установлен на этом сервере").formatted(Formatting.RED), false);
			return;
		}
		PacketByteBuf buf = PacketByteBufs.create();
		boolean hasPoints = pos1 != null && pos2 != null;
		buf.writeBoolean(hasPoints);
		if (hasPoints) {
			buf.writeBlockPos(pos1);
			buf.writeBlockPos(pos2);
		}
		ClientPlayNetworking.send(AutoDig.TOGGLE, buf);
	}

	private static void render(WorldRenderContext ctx) {
		if (pos1 == null && pos2 == null) return;
		VertexConsumerProvider consumers = ctx.consumers();
		if (consumers == null) return;

		Vec3d cam = ctx.camera().getPos();
		MatrixStack matrices = ctx.matrixStack();
		matrices.push();
		matrices.translate(-cam.x, -cam.y, -cam.z);

		VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());
		if (pos1 != null) WorldRenderer.drawBox(matrices, lines, new Box(pos1).expand(0.005), 0.2f, 1f, 0.2f, 1f);
		if (pos2 != null) WorldRenderer.drawBox(matrices, lines, new Box(pos2).expand(0.005), 0.2f, 0.6f, 1f, 1f);
		if (pos1 != null && pos2 != null) {
			Box area = new Box(
					Math.min(pos1.getX(), pos2.getX()), Math.min(pos1.getY(), pos2.getY()), Math.min(pos1.getZ(), pos2.getZ()),
					Math.max(pos1.getX(), pos2.getX()) + 1, Math.max(pos1.getY(), pos2.getY()) + 1, Math.max(pos1.getZ(), pos2.getZ()) + 1
			).expand(0.01);
			WorldRenderer.drawBox(matrices, lines, area, 1f, 0.85f, 0.1f, 1f);
		}
		matrices.pop();

		if (consumers instanceof VertexConsumerProvider.Immediate immediate) {
			immediate.draw(RenderLayer.getLines());
		}
	}
}
