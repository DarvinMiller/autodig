package com.autodig;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * Одно задание на копание: прямоугольная область, которая проходится
 * слоями сверху вниз, «змейкой», начиная с угла, ближайшего к игроку.
 */
public class DigJob {
	final UUID playerId;
	final ServerWorld world;
	final int minX, minY, minZ, maxX, maxY, maxZ;
	final int sizeX, sizeY, sizeZ;
	final boolean flipX, flipZ;
	final long total;

	long index = 0;
	long broken = 0;

	DigJob(UUID playerId, ServerWorld world, BlockPos a, BlockPos b, double playerX, double playerZ) {
		this.playerId = playerId;
		this.world = world;
		minX = Math.min(a.getX(), b.getX());
		maxX = Math.max(a.getX(), b.getX());
		minZ = Math.min(a.getZ(), b.getZ());
		maxZ = Math.max(a.getZ(), b.getZ());
		minY = Math.max(Math.min(a.getY(), b.getY()), world.getBottomY());
		maxY = Math.min(Math.max(a.getY(), b.getY()), world.getTopY() - 1);
		sizeX = maxX - minX + 1;
		sizeZ = maxZ - minZ + 1;
		sizeY = Math.max(0, maxY - minY + 1);
		total = (long) sizeX * sizeZ * sizeY;

		// Начинаем с угла, который ближе всего к тому месту, где стоит игрок.
		flipX = Math.abs(playerX - (maxX + 0.5)) < Math.abs(playerX - (minX + 0.5));
		flipZ = Math.abs(playerZ - (maxZ + 0.5)) < Math.abs(playerZ - (minZ + 0.5));
	}

	boolean isDone() {
		return index >= total;
	}

	/** Координаты i-го блока обхода. Чётные слои идут вперёд, нечётные — назад, чтобы не возвращаться через всю область. */
	BlockPos posAt(long i) {
		long area = (long) sizeX * sizeZ;
		int layer = (int) (i / area);
		long rem = i % area;
		if ((layer & 1) == 1) rem = area - 1 - rem;
		int row = (int) (rem / sizeX);
		int col = (int) (rem % sizeX);
		if ((row & 1) == 1) col = sizeX - 1 - col;
		int x = flipX ? maxX - col : minX + col;
		int z = flipZ ? maxZ - row : minZ + row;
		return new BlockPos(x, maxY - layer, z);
	}

	int percent() {
		return total == 0 ? 100 : (int) (index * 100 / total);
	}
}
