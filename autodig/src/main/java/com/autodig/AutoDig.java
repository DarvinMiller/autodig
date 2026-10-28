package com.autodig;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkSectionPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class AutoDig implements ModInitializer {
	public static final String MOD_ID = "autodig";
	/** Клиент -> сервер: начать/остановить. Данные: boolean hasPoints, [BlockPos a, BlockPos b]. */
	public static final Identifier TOGGLE = new Identifier(MOD_ID, "toggle");

	private static final int DEFAULT_SPEED = 2;
	/** Сколько позиций (включая воздух) можно просмотреть за тик, чтобы не лагал сервер. */
	private static final int MAX_SCAN_PER_TICK = 4096;

	private static final Map<UUID, DigJob> JOBS = new HashMap<>();
	private static final Map<UUID, BlockPos[]> SELECTIONS = new HashMap<>();
	private static final Map<UUID, Integer> SPEED = new HashMap<>();
	private static final Map<UUID, Boolean> PICKUP = new HashMap<>();

	@Override
	public void onInitialize() {
		ServerPlayNetworking.registerGlobalReceiver(TOGGLE, (server, player, handler, buf, sender) -> {
			boolean hasPoints = buf.readBoolean();
			BlockPos a = hasPoints ? buf.readBlockPos() : null;
			BlockPos b = hasPoints ? buf.readBlockPos() : null;
			server.execute(() -> {
				if (JOBS.containsKey(player.getUuid())) {
					stop(player);
				} else if (!hasPoints) {
					msg(player, "Сначала отметь две точки (по умолчанию V и B).", Formatting.RED);
				} else {
					SELECTIONS.put(player.getUuid(), new BlockPos[]{a, b});
					start(player, a, b);
				}
			});
		});

		ServerTickEvents.END_SERVER_TICK.register(AutoDig::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> JOBS.remove(handler.player.getUuid()));
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				CommandManager.literal("autodig")
						.then(CommandManager.literal("pos1")
								.executes(ctx -> setPos(ctx, 0, null))
								.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
										.executes(ctx -> setPos(ctx, 0, BlockPosArgumentType.getBlockPos(ctx, "pos")))))
						.then(CommandManager.literal("pos2")
								.executes(ctx -> setPos(ctx, 1, null))
								.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
										.executes(ctx -> setPos(ctx, 1, BlockPosArgumentType.getBlockPos(ctx, "pos")))))
						.then(CommandManager.literal("start").executes(ctx -> {
							ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
							BlockPos[] sel = SELECTIONS.get(player.getUuid());
							if (sel == null || sel[0] == null || sel[1] == null) {
								msg(player, "Сначала отметь обе точки: /autodig pos1 и /autodig pos2", Formatting.RED);
								return 0;
							}
							start(player, sel[0], sel[1]);
							return 1;
						}))
						.then(CommandManager.literal("stop").executes(ctx -> {
							stop(ctx.getSource().getPlayerOrThrow());
							return 1;
						}))
						.then(CommandManager.literal("speed")
								.then(CommandManager.argument("blocksPerTick", IntegerArgumentType.integer(1, 64)).executes(ctx -> {
									ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
									int speed = IntegerArgumentType.getInteger(ctx, "blocksPerTick");
									SPEED.put(player.getUuid(), speed);
									msg(player, "Скорость: " + speed + " блок(ов) за тик (" + speed * 20 + "/сек)", Formatting.GREEN);
									return 1;
								})))
						.then(CommandManager.literal("pickup")
								.then(CommandManager.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
									ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
									boolean on = BoolArgumentType.getBool(ctx, "enabled");
									PICKUP.put(player.getUuid(), on);
									msg(player, on ? "Добыча складывается в инвентарь" : "Добыча падает на землю", Formatting.GREEN);
									return 1;
								})))
		));
	}

	private static int setPos(CommandContext<ServerCommandSource> ctx, int which, BlockPos pos) throws CommandSyntaxException {
		ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
		if (pos == null) {
			HitResult hit = player.raycast(64, 0, false);
			pos = hit.getType() == HitResult.Type.BLOCK ? ((BlockHitResult) hit).getBlockPos() : player.getBlockPos();
		}
		SELECTIONS.computeIfAbsent(player.getUuid(), k -> new BlockPos[2])[which] = pos;
		msg(player, "Точка " + (which + 1) + ": " + pos.getX() + " " + pos.getY() + " " + pos.getZ(), Formatting.GREEN);
		return 1;
	}

	private static void start(ServerPlayerEntity player, BlockPos a, BlockPos b) {
		ServerWorld world = (ServerWorld) player.getWorld();
		DigJob job = new DigJob(player.getUuid(), world, a, b, player.getX(), player.getZ());
		if (job.total == 0) {
			msg(player, "Область вне границ мира по высоте.", Formatting.RED);
			return;
		}
		JOBS.put(player.getUuid(), job);
		msg(player, "Начинаю копать " + job.sizeX + "x" + job.sizeY + "x" + job.sizeZ + " (" + job.total + " блоков). Повторное нажатие — стоп.", Formatting.GREEN);
	}

	private static void stop(ServerPlayerEntity player) {
		DigJob job = JOBS.remove(player.getUuid());
		if (job == null) {
			msg(player, "Сейчас ничего не копается.", Formatting.YELLOW);
		} else {
			msg(player, "Остановлено. Вскопано блоков: " + job.broken, Formatting.YELLOW);
		}
	}

	private static void tick(MinecraftServer server) {
		Iterator<DigJob> it = JOBS.values().iterator();
		while (it.hasNext()) {
			DigJob job = it.next();
			ServerPlayerEntity player = server.getPlayerManager().getPlayer(job.playerId);
			if (player == null) {
				it.remove();
				continue;
			}
			if (player.getWorld() != job.world || player.isDead()) continue; // ждём, пока игрок вернётся

			String stopReason = step(job, player);
			if (stopReason != null) {
				it.remove();
				msg(player, stopReason, Formatting.RED);
			} else if (job.isDone()) {
				it.remove();
				msg(player, "Готово! Вскопано блоков: " + job.broken, Formatting.GREEN);
			} else if (server.getTicks() % 10 == 0) {
				player.sendMessage(Text.literal("Копаю: " + job.percent() + "% (" + job.broken + " блоков)").formatted(Formatting.GOLD), true);
			}
		}
	}

	/** Ломает до N блоков. Возвращает причину остановки или null. */
	private static String step(DigJob job, ServerPlayerEntity player) {
		int budget = SPEED.getOrDefault(player.getUuid(), DEFAULT_SPEED);
		boolean pickup = PICKUP.getOrDefault(player.getUuid(), true);
		ServerWorld world = job.world;

		for (int scanned = 0; scanned < MAX_SCAN_PER_TICK && budget > 0 && !job.isDone(); scanned++) {
			BlockPos pos = job.posAt(job.index);
			if (!world.isChunkLoaded(ChunkSectionPos.getSectionCoord(pos.getX()), ChunkSectionPos.getSectionCoord(pos.getZ()))) {
				return null; // чанк не загружен — подождём
			}

			BlockState state = world.getBlockState(pos);
			if (state.isAir() || state.getBlock() instanceof FluidBlock || state.getHardness(world, pos) < 0) {
				job.index++;
				continue;
			}

			ItemStack tool = player.getMainHandStack();
			if (!player.isCreative() && tool.isDamageable() && tool.getMaxDamage() - tool.getDamage() <= 1) {
				return "Инструмент почти сломан — копание остановлено. Возьми другой и запусти снова.";
			}

			// Ломаем как сам игрок: учитываются инструмент, зачарования, режим игры, защита территорий.
			if (player.interactionManager.tryBreakBlock(pos)) {
				job.broken++;
				if (pickup) collectDrops(world, pos, player);
			}
			job.index++;
			budget--;
		}
		return null;
	}

	private static void collectDrops(ServerWorld world, BlockPos pos, ServerPlayerEntity player) {
		Box box = new Box(pos).expand(1.0);
		for (ItemEntity item : world.getEntitiesByClass(ItemEntity.class, box, e -> e.isAlive())) {
			ItemStack stack = item.getStack().copy();
			player.getInventory().insertStack(stack);
			if (stack.isEmpty()) {
				item.discard();
			} else {
				item.setStack(stack); // не влезло — остаётся лежать
			}
		}
		for (ExperienceOrbEntity orb : world.getEntitiesByClass(ExperienceOrbEntity.class, box, e -> e.isAlive())) {
			player.addExperience(orb.getExperienceAmount());
			orb.discard();
		}
	}

	private static void msg(ServerPlayerEntity player, String text, Formatting color) {
		player.sendMessage(Text.literal("[AutoDig] ").formatted(Formatting.GRAY).append(Text.literal(text).formatted(color)), false);
	}
}
