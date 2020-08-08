package noobanidus.mods.lootr.util;

import net.minecraft.block.*;
import net.minecraft.entity.item.minecart.ContainerMinecartEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.server.ServerLifecycleHooks;
import noobanidus.mods.lootr.Lootr;
import noobanidus.mods.lootr.events.HandleBreak;
import noobanidus.mods.lootr.init.ModBlocks;
import noobanidus.mods.lootr.tiles.ILootTile;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.LinkedHashSet;

@SuppressWarnings({"unused", "WeakerAccess"})
@Mod.EventBusSubscriber(modid = Lootr.MODID)
public class TickManager {
  @SuppressWarnings("FieldCanBeLocal")
  private static int MAX_COUNTER = 10 * 50;
  private static final Object lock = new Object();
  private static boolean ticking = false;
  private static final LinkedHashSet<ITicker> waitList = new LinkedHashSet<>();
  private static final LinkedHashSet<ITicker> tickList = new LinkedHashSet<>();
  private static int integrated = -1;

  @SubscribeEvent
  public static void tick(TickEvent event) {
    ticking = true;
    if (event.side == LogicalSide.CLIENT && event.phase == TickEvent.Phase.END && event.type == TickEvent.Type.CLIENT) {
      if (integrated == 0) {
        synchronized (lock) {
          ticking = true;
          tickList.clear();
          ticking = false;
        }
      }
    } else if (event.side == LogicalSide.SERVER && event.phase == TickEvent.Phase.END && event.type == TickEvent.Type.SERVER) {
      if (integrated == -1) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
          integrated = 1;
        } else {
          integrated = 0;
        }
      }
      synchronized (lock) {
        ticking = true;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (!tickList.isEmpty()) {
          Iterator<ITicker> iterator = tickList.iterator();
          while (iterator.hasNext()) {
            ITicker ticker = iterator.next();
            if (ticker.getCounter() > MAX_COUNTER) {
              Lootr.LOG.debug("Ticker expired: " + ticker);
              iterator.remove();
              continue;
            }
            if (ticker.run()) {
              Lootr.LOG.debug("Successfully executed ticker: " + ticker);
              iterator.remove();
              continue;
            }
            if (ticker.invalid()) {
              Lootr.LOG.debug("Invalid ticker removed: " + ticker);
              iterator.remove();
            }
          }
        }
        tickList.addAll(waitList);
        ticking = false;
        waitList.clear();
      }
    }
  }

  public static void addTicker(ITicker ticker) {
    synchronized (lock) {
      if (ticking) {
        Lootr.LOG.debug("Adding new ticker to the wait list: " + ticker);
        waitList.add(ticker);
      } else {
        Lootr.LOG.debug("Adding new ticker to the tick list: " + ticker);
        tickList.add(ticker);
      }
    }
  }

  public static void addTicker(TileEntity tile, BlockPos pos, DimensionType type, ResourceLocation table, long seed) {
    addTicker(new TileTicker(tile, pos, type, table, seed));
  }

  public static void addTicker(ContainerMinecartEntity entity, long seed, ResourceLocation table, BlockPos pos, DimensionType dim) {
    addTicker(new EntityTicker(entity, seed, table, pos, dim));
  }

  public interface ITicker {
    int getCounter();

    boolean invalid();

    boolean run();
  }

  public static class EntityTicker implements ITicker {
    private final ContainerMinecartEntity entity;
    private int counter = 0;
    private long seed;
    private ResourceLocation table;
    private BlockPos pos;
    private DimensionType dim;

    public EntityTicker(ContainerMinecartEntity entity, long seed, ResourceLocation table, BlockPos pos, DimensionType dim) {
      this.entity = entity;
      this.seed = seed;
      this.table = table;
      this.pos = pos;
      this.dim = dim;
    }

    @Override
    public int getCounter() {
      return counter;
    }

    @Override
    public boolean invalid() {
      if (!entity.isAddedToWorld()) {
        return false;
      }

      return !entity.isAlive();
    }

    @Override
    public boolean run() {
      if (entity.ticksExisted <= 50) {
        return false;
      }

      if (!entity.isAddedToWorld()) {
        return false;
      }

      if (!entity.world.isAreaLoaded(pos, 1)) {
        counter++;
        return false;
      }

      entity.dropContentsWhenDead(false);
      entity.remove();
      World world = entity.world;
      world.removeTileEntity(pos);
      Lootr.LOG.debug("Calling setBlockState for entity ticker.");
      world.setBlockState(pos, ModBlocks.CHEST.getDefaultState());
      TileEntity te = world.getTileEntity(pos);
      if (te instanceof ILootTile) {
        ((ILootTile) te).setTable(table);
        ((ILootTile) te).setSeed(seed);
      }

      return true;
    }

    @Override
    public String toString() {
      return "EntityTicker{" +
          "entity=" + entity +
          ", counter=" + counter +
          ", seed=" + seed +
          ", table=" + table +
          ", pos=" + pos +
          ", dim=" + dim +
          '}';
    }
  }

  public static class TileTicker implements ITicker {
    private final TileEntity ref;
    private int ticker = 50;
    private int counter = 0;
    private long seed;
    private ResourceLocation table;
    private BlockPos pos;
    private DimensionType dim;

    public TileTicker(TileEntity tile, BlockPos pos, @Nullable DimensionType dim, ResourceLocation table, long seed) {
      this.ref = tile;
      this.table = table;
      if (table == null) {
        this.table = null;
      }
      this.seed = seed;
      this.pos = pos;
      this.dim = dim;
    }

    public int getCounter() {
      return counter;
    }

    public boolean invalid() {
      TileEntity te = ref;

      if (te.getWorld() == null) {
        return false;
      }

      if (te.getWorld().isRemote()) {
        return true;
      }

      return !te.getWorld().isAreaLoaded(pos, 1);
    }

    public boolean run() {
      if (ticker-- > 0) {
        return false;
      }

      TileEntity te = ref;

      if (te.getWorld() == null) {
        counter++;
        return false; // still valid
      }

      World world = te.getWorld();
      if (world.isRemote()) {
        return false; // invalid
      }

      if (!world.isAreaLoaded(pos, 1)) {
        return false;
      }

      BlockPos pos = te.getPos();

      BlockState state = world.getBlockState(pos);
      Block block = state.getBlock();

      BlockState replacementState = getReplacement(block, state);

      if (replacementState != null) {
        Lootr.LOG.debug("Calling setBlockState to replace ticker.");
        world.setBlockState(pos, replacementState);
      }
      te = world.getTileEntity(pos);
      if (te instanceof ILootTile) {
        ((ILootTile) te).setSeed(seed);
        ((ILootTile) te).setTable(table);
      }
      return true;
    }

    @Override
    public String toString() {
      return "Ticker{" +
          "ref=" + ref +
          ", counter=" + counter +
          ", seed=" + seed +
          ", table=" + table +
          ", pos=" + pos +
          ", dim=" + dim +
          '}';
    }
  }

  public static BlockState getReplacement(Block block, BlockState state) {
    if (!HandleBreak.specialLootChests.contains(block)) {
      if (block == Blocks.CHEST) {
        return ModBlocks.CHEST.getDefaultState().with(ChestBlock.FACING, state.get(ChestBlock.FACING)).with(ChestBlock.WATERLOGGED, state.get(ChestBlock.WATERLOGGED));
      } else if (block == Blocks.TRAPPED_CHEST) {
        return ModBlocks.TRAPPED_CHEST.getDefaultState().with(ChestBlock.FACING, state.get(ChestBlock.FACING)).with(ChestBlock.WATERLOGGED, state.get(ChestBlock.WATERLOGGED));
      } else if (block == Blocks.BARREL) {
        return ModBlocks.BARREL.getDefaultState().with(BarrelBlock.PROPERTY_FACING, state.get(BarrelBlock.PROPERTY_FACING)).with(BarrelBlock.PROPERTY_OPEN, state.get(BarrelBlock.PROPERTY_OPEN));
      }
    }

    return Blocks.AIR.getDefaultState();
  }
}
