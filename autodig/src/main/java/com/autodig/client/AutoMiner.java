package com.autodig.client;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.HashSet;
import java.util.Set;

/**
 * Копает область как обычный игрок: по одному блоку, ряд за рядом («змейкой»),
 * слоями сверху вниз, начиная с угла, ближайшего к игроку.
 * Голова поворачивается плавно, блок ломается только когда на него наведён прицел,
 * между блоками — короткая пауза. Пакеты на сервер шлёт только сама ванилла,
 * ровно те же, что при ручной игре.
 */
public final class AutoMiner {
	/** Максимальный поворот головы за тик, в градусах. */
	private static final float MAX_TURN = 12f;
	/** Пауза между блоками, тики (случайно в этом диапазоне). */
	private static final int PAUSE_MIN = 3, PAUSE_MAX = 8;
	/** Сколько тиков можно не продвигаться к цели, прежде чем пропустить её. */
	private static final int STUCK_TICKS = 100;
	/** Остановиться, если здоровье упало до этого значения (6 = 3 сердца). */
	private static final float MIN_HEALTH = 6f;
	private static final int SCAN_PER_TICK = 2048;

	private static final Random RANDOM = Random.create();

	private static boolean active;
	private static int minX, minY, minZ, maxX, maxZ, topY;
	private static int sizeX, sizeZ;
	private static boolean flipX, flipZ;
	private static int layer, layerIndex;
	private static int mined;

	private static BlockPos target;
	private static int pause;
	private static float turnVelYaw, turnVelPitch;
	private static int stuckTicks;
	private static double walkBestDist;
	private static final Set<BlockPos> skipped = new HashSet<>();

	private AutoMiner() {
	}

	public static boolean isActive() {
		return active;
	}

	public static BlockPos getTarget() {
		return target;
	}

	public static void start(MinecraftClient client, BlockPos a, BlockPos b) {
		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;
		minX = Math.min(a.getX(), b.getX());
		maxX = Math.max(a.getX(), b.getX());
		minZ = Math.min(a.getZ(), b.getZ());
		maxZ = Math.max(a.getZ(), b.getZ());
		minY = Math.max(Math.min(a.getY(), b.getY()), world.getBottomY());
		topY = Math.min(Math.max(a.getY(), b.getY()), world.getTopY() - 1);
		sizeX = maxX - minX + 1;
		sizeZ = maxZ - minZ + 1;
		flipX = Math.abs(player.getX() - (maxX + 0.5)) < Math.abs(player.getX() - (minX + 0.5));
		flipZ = Math.abs(player.getZ() - (maxZ + 0.5)) < Math.abs(player.getZ() - (minZ + 0.5));
		layer = topY;
		layerIndex = 0;
		mined = 0;
		target = null;
		pause = 0;
		skipped.clear();
		active = true;
		msg(client, "Старт. N — стоп.", Formatting.GREEN);
	}

	public static void stop(MinecraftClient client, String reason, Formatting color) {
		if (!active) return;
		active = false;
		target = null;
		releaseKeys(client);
		if (client.interactionManager != null && client.interactionManager.isBreakingBlock()) {
			client.interactionManager.cancelBlockBreaking();
		}
		String extra = skipped.isEmpty() ? "" : ", пропущено " + skipped.size();
		msg(client, reason + " Вскопано " + mined + extra, color);
	}

	public static void tick(MinecraftClient client) {
		if (!active) return;
		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;
		if (player == null || world == null || client.interactionManager == null) {
			active = false;
			return;
		}
		if (player.isDead()) {
			stop(client, "Ты умер.", Formatting.RED);
			return;
		}
		if (!player.isCreative() && player.getHealth() <= MIN_HEALTH) {
			stop(client, "Мало здоровья.", Formatting.RED);
			return;
		}
		if (client.currentScreen != null) {
			releaseKeys(client);
			return;
		}

		// Текущий блок сломан или стал недоступен — переходим к следующему.
		if (target != null) {
			BlockState state = world.getBlockState(target);
			if (isLoaded(world, target) && !isMineable(world, target, state)) {
				if (state.isAir()) {
					mined++;
					pause = PAUSE_MIN + RANDOM.nextInt(PAUSE_MAX - PAUSE_MIN + 1);
				}
				target = null;
			}
		}
		if (target == null) {
			target = nextTarget(world);
			stuckTicks = 0;
			walkBestDist = Double.MAX_VALUE;
			if (target == null) {
				if (layer < minY) stop(client, "Готово!", Formatting.GREEN);
				return;
			}
		}

		double reach = client.interactionManager.getReachDistance();
		Vec3d eye = player.getEyePos();
		Vec3d aim = aimPoint(eye, target);

		if (!isLoaded(world, target) || eye.squaredDistanceTo(Vec3d.ofCenter(target)) > (reach - 0.5) * (reach - 0.5)) {
			walkTowards(client, target);
			return;
		}
		releaseKeys(client);

		boolean aimed = turnTowards(player, aim);
		if (pause > 0) {
			pause--;
			return;
		}
		if (!aimed) return;

		HitResult hit = player.raycast(reach, 1f, false);
		BlockPos breakPos = null;
		if (hit.getType() == HitResult.Type.BLOCK) {
			BlockPos hitPos = ((BlockHitResult) hit).getBlockPos();
			if (hitPos.equals(target)) {
				breakPos = target;
			} else if (inArea(hitPos) && isMineable(world, hitPos, world.getBlockState(hitPos))) {
				breakPos = hitPos; // загораживает цель, но тоже в области — ломаем его
			}
		}
		if (breakPos == null) {
			if (++stuckTicks > STUCK_TICKS) skip(target);
			return;
		}

		BlockState state = world.getBlockState(breakPos);
		if (!client.interactionManager.isBreakingBlock() && !player.isCreative()) {
			// Инструмент выбираем только в начале ломания блока — без лишней смены слотов.
			selectBestTool(player, state);
			if (isNearlyBroken(player.getMainHandStack())) {
				stop(client, "Инструменты почти сломаны.", Formatting.RED);
				return;
			}
		}
		Direction side = ((BlockHitResult) hit).getSide();
		if (client.interactionManager.updateBlockBreakingProgress(breakPos, side)) {
			client.particleManager.addBlockBreakingParticles(breakPos, side);
			player.swingHand(Hand.MAIN_HAND);
		}
		stuckTicks = 0;
	}

	/** Следующий блок по порядку обхода; null — либо ещё ищем (большая область), либо всё (layer < minY). */
	private static BlockPos nextTarget(ClientWorld world) {
		int area = sizeX * sizeZ;
		int scanned = 0;
		while (layer >= minY) {
			while (layerIndex < area) {
				if (scanned++ >= SCAN_PER_TICK) return null;
				BlockPos pos = posAt(layerIndex);
				if (!isLoaded(world, pos) || isMineable(world, pos, world.getBlockState(pos))) return pos;
				layerIndex++;
			}
			// Слой пройден. Если что-то осталось (например, осыпался гравий) — ещё один проход.
			if (layerHasLeftovers(world)) {
				layerIndex = 0;
				continue;
			}
			layer--;
			layerIndex = 0;
		}
		return null;
	}

	/** Змейка: ряды вдоль X, на чётных слоях от стартового угла, на нечётных — обратно. */
	private static BlockPos posAt(int i) {
		int area = sizeX * sizeZ;
		int rem = ((topY - layer) & 1) == 1 ? area - 1 - i : i;
		int row = rem / sizeX;
		int col = rem % sizeX;
		if ((row & 1) == 1) col = sizeX - 1 - col;
		int x = flipX ? maxX - col : minX + col;
		int z = flipZ ? maxZ - row : minZ + row;
		return new BlockPos(x, layer, z);
	}

	private static boolean layerHasLeftovers(ClientWorld world) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				m.set(x, layer, z);
				if (isMineable(world, m, world.getBlockState(m))) return true;
			}
		}
		return false;
	}

	private static void walkTowards(MinecraftClient client, BlockPos pos) {
		ClientPlayerEntity player = client.player;
		double dx = pos.getX() + 0.5 - player.getX();
		double dz = pos.getZ() + 0.5 - player.getZ();
		double dist = Math.sqrt(dx * dx + dz * dz);
		if (dist < walkBestDist - 0.2) {
			walkBestDist = dist;
			stuckTicks = 0;
		} else if (++stuckTicks > STUCK_TICKS) {
			skip(pos);
			releaseKeys(client);
			return;
		}

		// Прицел всё время держим на целевом блоке, а идём туда, куда смотрим.
		turnTowards(player, aimPoint(player.getEyePos(), pos));
		float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
		boolean facing = Math.abs(MathHelper.wrapDegrees(yaw - player.getYaw())) < 30f;
		client.options.forwardKey.setPressed(facing);
		client.options.sprintKey.setPressed(facing && dist > 2.5);
		client.options.jumpKey.setPressed(facing && player.horizontalCollision && player.isOnGround());
	}

	/** Плавно поворачивает голову к точке. true — прицел уже на ней. */
	private static boolean turnTowards(ClientPlayerEntity player, Vec3d point) {
		Vec3d eye = player.getEyePos();
		double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
		float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
		float pitch = (float) (-MathHelper.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180.0 / Math.PI));
		return turn(player, yaw, pitch, 1.5f);
	}

	private static boolean turn(ClientPlayerEntity player, float yaw, float pitch, float tolerance) {
		float dYaw = MathHelper.wrapDegrees(yaw - player.getYaw());
		float dPitch = pitch - player.getPitch();
		if (Math.abs(dYaw) < 0.3f && Math.abs(dPitch) < 0.3f) {
			turnVelYaw = 0f;
			turnVelPitch = 0f;
			return true;
		}
		// Плавный поворот: скорость нарастает и затухает постепенно, без рывков.
		float wantYaw = MathHelper.clamp(dYaw * 0.3f, -MAX_TURN, MAX_TURN);
		float wantPitch = MathHelper.clamp(dPitch * 0.3f, -MAX_TURN, MAX_TURN);
		turnVelYaw += (wantYaw - turnVelYaw) * 0.5f;
		turnVelPitch += (wantPitch - turnVelPitch) * 0.5f;
		if (Math.abs(turnVelYaw) > Math.abs(dYaw)) turnVelYaw = dYaw;
		if (Math.abs(turnVelPitch) > Math.abs(dPitch)) turnVelPitch = dPitch;
		player.setYaw(player.getYaw() + turnVelYaw);
		player.setPitch(MathHelper.clamp(player.getPitch() + turnVelPitch, -90f, 90f));
		return Math.abs(dYaw - turnVelYaw) <= tolerance && Math.abs(dPitch - turnVelPitch) <= tolerance;
	}

	/** Центр грани блока, обращённой к игроку. */
	private static Vec3d aimPoint(Vec3d eye, BlockPos pos) {
		Vec3d c = Vec3d.ofCenter(pos);
		// Блоки ниже глаз копаем сверху (боковые грани обычно закрыты соседями).
		Direction side = eye.y > pos.getY() + 1
				? Direction.UP
				: Direction.getFacing((float) (eye.x - c.x), 0f, (float) (eye.z - c.z));
		return c.add(side.getOffsetX() * 0.45, side.getOffsetY() * 0.45, side.getOffsetZ() * 0.45);
	}

	private static void skip(BlockPos pos) {
		skipped.add(pos.toImmutable());
		if (pos.equals(target)) target = null;
		stuckTicks = 0;
	}

	private static boolean inArea(BlockPos p) {
		return p.getX() >= minX && p.getX() <= maxX && p.getZ() >= minZ && p.getZ() <= maxZ && p.getY() >= minY && p.getY() <= topY;
	}

	private static boolean isLoaded(ClientWorld world, BlockPos pos) {
		return world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
	}

	private static boolean isMineable(ClientWorld world, BlockPos pos, BlockState state) {
		if (state.isAir() || state.getBlock() instanceof FluidBlock || state.isOf(Blocks.BUBBLE_COLUMN)) return false;
		if (state.getHardness(world, pos) < 0) return false;
		if (skipped.contains(pos)) return false;
		for (Direction d : Direction.values()) {
			if (world.getFluidState(pos.offset(d)).isIn(FluidTags.LAVA)) {
				skipped.add(pos.toImmutable());
				return false;
			}
		}
		return true;
	}

	private static void selectBestTool(ClientPlayerEntity player, BlockState state) {
		PlayerInventory inv = player.getInventory();
		int bestSlot = inv.selectedSlot;
		float bestScore = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = inv.getStack(i);
			if (isNearlyBroken(stack)) continue;
			boolean drops = !state.isToolRequired() || stack.isSuitableFor(state);
			float score = (drops ? 1000f : 0f) + stack.getMiningSpeedMultiplier(state) + (i == inv.selectedSlot ? 0.01f : 0f);
			if (score > bestScore) {
				bestScore = score;
				bestSlot = i;
			}
		}
		inv.selectedSlot = bestSlot;
	}

	private static boolean isNearlyBroken(ItemStack stack) {
		return stack.isDamageable() && stack.getMaxDamage() - stack.getDamage() <= 1;
	}

	private static void releaseKeys(MinecraftClient client) {
		client.options.forwardKey.setPressed(false);
		client.options.jumpKey.setPressed(false);
		client.options.sprintKey.setPressed(false);
		if (client.player != null) client.player.setSprinting(false);
	}

	private static void msg(MinecraftClient client, String text, Formatting color) {
		if (client.player == null) return;
		client.player.sendMessage(Text.literal("[AutoDig] ").formatted(Formatting.GRAY).append(Text.literal(text).formatted(color)), true);
	}
}
