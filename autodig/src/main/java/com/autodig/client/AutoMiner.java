package com.autodig.client;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.Set;

/**
 * Копает область «руками» игрока: смотрит на блок, ломает его как при зажатой ЛКМ,
 * а когда рядом ничего не осталось — идёт к ближайшему невскопанному блоку.
 * Всё происходит только на клиенте, сервер видит обычного игрока.
 *
 * Порядок: слои сверху вниз, внутри слоя — сначала ближайшие к игроку блоки.
 */
public final class AutoMiner {
	/** Дальность, с которой ломаем (ванильная дальность в выживании). */
	private static final double REACH = 4.5;
	/** Сколько тиков можно идти к блоку, не приближаясь, прежде чем считать его недоступным. */
	private static final int STUCK_TICKS = 60;
	/** Остановиться, если здоровье упало до этого значения (6 = 3 сердца). */
	private static final float MIN_HEALTH = 6f;

	private static boolean active;
	private static int minX, minY, minZ, maxX, maxZ;
	private static int layer;
	private static int mined;

	private static BlockPos target;
	private static BlockPos walkTarget;
	private static double walkBestDist;
	private static int walkStuckTicks;
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
		ClientWorld world = client.world;
		minX = Math.min(a.getX(), b.getX());
		maxX = Math.max(a.getX(), b.getX());
		minZ = Math.min(a.getZ(), b.getZ());
		maxZ = Math.max(a.getZ(), b.getZ());
		minY = Math.max(Math.min(a.getY(), b.getY()), world.getBottomY());
		layer = Math.min(Math.max(a.getY(), b.getY()), world.getTopY() - 1);
		mined = 0;
		target = null;
		walkTarget = null;
		skipped.clear();
		active = true;
		msg(client, "Начинаю копать. Повторное нажатие — стоп.", Formatting.GREEN);
	}

	public static void stop(MinecraftClient client, String reason, Formatting color) {
		if (!active) return;
		active = false;
		target = null;
		walkTarget = null;
		releaseKeys(client);
		if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
		String extra = skipped.isEmpty() ? "" : ", пропущено: " + skipped.size();
		msg(client, reason + " Вскопано блоков: " + mined + extra, color);
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
			stop(client, "Ты умер — копание остановлено.", Formatting.RED);
			return;
		}
		if (!player.isCreative() && player.getHealth() <= MIN_HEALTH) {
			stop(client, "Мало здоровья — копание остановлено.", Formatting.RED);
			return;
		}
		if (client.currentScreen != null) {
			// Открыт инвентарь/меню — ставим на паузу.
			releaseKeys(client);
			return;
		}

		// Продолжаем ломать текущий блок, если он ещё есть и достаём до него.
		if (target != null) {
			BlockState state = world.getBlockState(target);
			if (state.isAir()) {
				mined++;
				target = null;
			} else if (!isMineable(world, target, state) || !inReach(player, target)) {
				target = null;
			}
		}

		if (target == null) target = findReachable(client, false);
		if (target == null) {
			BlockPos far = findNearestInLayer(client);
			if (far != null) {
				walkTo(client, far);
				return;
			}
			// Остались только блоки, на которых стоим, — ломаем их.
			target = findReachable(client, true);
			if (target == null) {
				// Слой закончен — спускаемся ниже.
				layer--;
				if (layer < minY) {
					stop(client, "Готово!", Formatting.GREEN);
				}
				return;
			}
		}

		releaseKeys(client);
		mine(client, target);

		if (player.age % 10 == 0) {
			player.sendMessage(Text.literal("Копаю: слой " + layer + " (осталось " + (layer - minY + 1) + "), вскопано " + mined)
					.formatted(Formatting.GOLD), true);
		}
	}

	private static void mine(MinecraftClient client, BlockPos pos) {
		ClientPlayerEntity player = client.player;
		BlockState state = client.world.getBlockState(pos);
		Vec3d center = Vec3d.ofCenter(pos);
		Vec3d eye = player.getEyePos();
		Direction side = Direction.getFacing((float) (eye.x - center.x), (float) (eye.y - center.y), (float) (eye.z - center.z));

		player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, center);
		if (!player.isCreative()) {
			selectBestTool(player, state);
			if (isNearlyBroken(player.getMainHandStack())) {
				stop(client, "Все подходящие инструменты почти сломаны.", Formatting.RED);
				return;
			}
		}
		if (client.interactionManager.updateBlockBreakingProgress(pos, side)) {
			client.particleManager.addBlockBreakingParticles(pos, side);
			player.swingHand(Hand.MAIN_HAND);
		}
	}

	/** Ближайший к глазам блок текущего слоя в зоне досягаемости. */
	private static BlockPos findReachable(MinecraftClient client, boolean allowSupport) {
		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;
		Vec3d eye = player.getEyePos();
		int r = (int) Math.ceil(REACH) + 1;
		int x0 = Math.max(minX, MathHelper.floor(eye.x) - r), x1 = Math.min(maxX, MathHelper.floor(eye.x) + r);
		int z0 = Math.max(minZ, MathHelper.floor(eye.z) - r), z1 = Math.min(maxZ, MathHelper.floor(eye.z) + r);

		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				m.set(x, layer, z);
				if (!inReach(player, m)) continue;
				BlockState state = world.getBlockState(m);
				if (!isMineable(world, m, state)) continue;
				if (!allowSupport && isSupporting(player, m)) continue;
				double d = eye.squaredDistanceTo(Vec3d.ofCenter(m));
				if (d < bestDist) {
					bestDist = d;
					best = m.toImmutable();
				}
			}
		}
		return best;
	}

	/** Ближайший (по горизонтали) блок текущего слоя, до которого не достаём — к нему нужно идти. */
	private static BlockPos findNearestInLayer(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				m.set(x, layer, z);
				if (skipped.contains(m)) continue;
				boolean loaded = world.getChunkManager().isChunkLoaded(x >> 4, z >> 4);
				if (loaded && !isMineable(world, m, world.getBlockState(m))) continue;
				if (inReach(player, m)) continue;
				double dx = x + 0.5 - player.getX(), dz = z + 0.5 - player.getZ();
				double d = dx * dx + dz * dz;
				if (d < bestDist) {
					bestDist = d;
					best = m.toImmutable();
				}
			}
		}
		return best;
	}

	private static void walkTo(MinecraftClient client, BlockPos pos) {
		ClientPlayerEntity player = client.player;
		double dx = pos.getX() + 0.5 - player.getX();
		double dz = pos.getZ() + 0.5 - player.getZ();
		double dist = Math.sqrt(dx * dx + dz * dz);

		if (!pos.equals(walkTarget)) {
			walkTarget = pos;
			walkBestDist = dist;
			walkStuckTicks = 0;
		} else if (dist < walkBestDist - 0.3) {
			walkBestDist = dist;
			walkStuckTicks = 0;
		} else if (++walkStuckTicks > STUCK_TICKS) {
			// Не можем подойти — пропускаем этот блок.
			skipped.add(pos);
			walkTarget = null;
			releaseKeys(client);
			return;
		}

		player.setYaw((float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90f);
		player.setPitch(30f);
		client.options.forwardKey.setPressed(true);
		client.options.jumpKey.setPressed(player.horizontalCollision && player.isOnGround());
	}

	private static boolean isMineable(ClientWorld world, BlockPos pos, BlockState state) {
		if (state.isAir() || state.getBlock() instanceof FluidBlock || state.isOf(Blocks.BUBBLE_COLUMN)) return false;
		if (state.getHardness(world, pos) < 0) return false; // бедрок и т.п.
		if (skipped.contains(pos)) return false;
		// Не трогаем блоки рядом с лавой, чтобы не выпустить её на себя.
		for (Direction d : Direction.values()) {
			if (world.getFluidState(pos.offset(d)).isIn(FluidTags.LAVA)) {
				skipped.add(pos.toImmutable());
				return false;
			}
		}
		return true;
	}

	private static boolean inReach(ClientPlayerEntity player, BlockPos pos) {
		return player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= REACH * REACH;
	}

	/** Стоит ли игрок на этом блоке. */
	private static boolean isSupporting(ClientPlayerEntity player, BlockPos pos) {
		Box feet = player.getBoundingBox();
		Box below = new Box(feet.minX, feet.minY - 0.5, feet.minZ, feet.maxX, feet.minY, feet.maxZ);
		return below.intersects(new Box(pos));
	}

	/** Берёт в руку лучший инструмент из хотбара: сначала тот, с которого падает дроп, потом самый быстрый. */
	private static void selectBestTool(ClientPlayerEntity player, BlockState state) {
		PlayerInventory inv = player.getInventory();
		int bestSlot = inv.selectedSlot;
		float bestScore = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = inv.getStack(i);
			if (isNearlyBroken(stack)) continue;
			boolean drops = !state.isToolRequired() || stack.isSuitableFor(state);
			float score = (drops ? 1000f : 0f) + stack.getMiningSpeedMultiplier(state);
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
	}

	private static void msg(MinecraftClient client, String text, Formatting color) {
		if (client.player == null) return;
		client.player.sendMessage(Text.literal("[AutoDig] ").formatted(Formatting.GRAY).append(Text.literal(text).formatted(color)), false);
	}
}
