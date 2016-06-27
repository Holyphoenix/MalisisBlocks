/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Ordinastie
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package net.malisis.blocks.tileentity;

import java.util.Random;

import net.malisis.blocks.MalisisBlocks;
import net.malisis.blocks.MalisisBlocksSettings;
import net.malisis.blocks.ProxyAccess;
import net.malisis.blocks.block.VanishingBlock;
import net.malisis.core.util.EntityUtils;
import net.malisis.core.util.ItemUtils;
import net.malisis.core.util.MBlockState;
import net.malisis.core.util.Silenced;
import net.malisis.core.util.TileEntityUtils;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.apache.commons.lang3.ArrayUtils;

public class VanishingTileEntity extends TileEntity implements ITickable
{
	public final static int maxTransitionTime = 8;
	public final static int maxVibratingTime = 15;

	protected IBlockState copiedState;
	protected TileEntity copiedTileEntity;
	protected VanishingBlock.Type frameType;
	protected boolean powered;
	// animation purpose
	protected int duration = maxTransitionTime;
	protected int transitionTimer;
	protected boolean inTransition;
	protected boolean vibrating;
	protected int vibratingTimer;

	private final Random rand = new Random();

	private Block[] excludes = new Block[] { MalisisBlocks.Blocks.vanishingBlock, Blocks.AIR, Blocks.LADDER, Blocks.STONE_BUTTON,
			Blocks.WOODEN_BUTTON, Blocks.LEVER, Blocks.VINE };

	public boolean blockDrawn = true;

	public VanishingTileEntity()
	{
		this.frameType = VanishingBlock.Type.WOOD;
		ProxyAccess.get(getWorld());
	}

	public VanishingTileEntity(VanishingBlock.Type frameType)
	{
		this.frameType = frameType;
	}

	public VanishingBlock.Type getType()
	{
		return frameType;
	}

	public IBlockState getCopiedState()
	{
		return copiedState;
	}

	public TileEntity getCopiedTileEntity()
	{
		return copiedTileEntity;
	}

	public int getDuration()
	{
		return duration;
	}

	public boolean isPowered()
	{
		return powered;
	}

	public boolean isInTransition()
	{
		return inTransition;
	}

	public boolean isVibrating()
	{
		return vibrating;
	}

	public int getTransitionTimer()
	{
		return transitionTimer;
	}

	public void setBlockState(IBlockState state)
	{
		this.copiedState = state;
	}

	public boolean applyItemStack(ItemStack itemStack, EntityPlayer player, EnumFacing side, float hitX, float hitY, float hitZ)
	{
		ItemStack is = ItemUtils.getItemStackFromState(getCopiedState());

		if (!setBlockState(itemStack, player, side, hitX, hitY, hitZ))
			return false;

		EntityUtils.spawnEjectedItem(worldObj, pos, is);

		if (itemStack == null)
			return true;

		if (!player.capabilities.isCreativeMode)
			itemStack.stackSize--;

		((World) ProxyAccess.get(worldObj)).notifyNeighborsOfStateChange(pos, getCopiedState().getBlock());
		return true;
	}

	public boolean setBlockState(ItemStack itemStack, EntityPlayer p, EnumFacing side, float hitX, float hitY, float hitZ)
	{
		if (itemStack == null)
		{
			copiedState = null;
			copiedTileEntity = null;
			TileEntityUtils.notifyUpdate(this);
			return true;
		}

		IBlockState state = ItemUtils.getStateFromItemStack(itemStack);
		if (state == null || ArrayUtils.contains(excludes, state.getBlock()))
			return false;

		World proxy = (World) ProxyAccess.get(getWorld());
		copiedState = state;
		initCopiedTileEntity();
		Silenced.exec(() -> {
			copiedState = state.getBlock().onBlockPlaced(proxy, pos, side, hitX, hitY, hitZ, itemStack.getMetadata(), p);
			if (p != null)
				copiedState.getBlock().onBlockPlacedBy(proxy, pos, copiedState, p, itemStack);
		});

		TileEntityUtils.notifyUpdate(this);
		return true;
	}

	private void initCopiedTileEntity()
	{
		copiedTileEntity = copiedState.getBlock().createTileEntity(getWorld(), copiedState);
		if (copiedTileEntity != null)
		{
			copiedTileEntity.setWorldObj((World) ProxyAccess.get(getWorld()));
			copiedTileEntity.setPos(pos);
		}
	}

	public void ejectCopiedState()
	{
		ItemStack is = ItemUtils.getItemStackFromState(getCopiedState());
		EntityUtils.spawnEjectedItem(worldObj, pos, is);
	}

	public boolean setPowerState(boolean powered)
	{
		if (powered == this.powered)
			return false;

		if (!inTransition)
			this.transitionTimer = powered ? 0 : getDuration();
		this.powered = powered;
		this.inTransition = true;
		//will probably break
		worldObj.setBlockState(pos, getWorld().getBlockState(pos).withProperty(VanishingBlock.TRANSITION, true));

		blockDrawn = false;

		return true;
	}

	@Override
	public void update()
	{
		if (!inTransition && !powered)
		{
			if (!worldObj.isRemote)
				return;
			float r = rand.nextFloat();
			boolean b = r < MalisisBlocksSettings.vanishingGlitchChance.get();
			if (b && MalisisBlocksSettings.enableVanishingGlitch.get() && !vibrating)
			{
				vibrating = true;
				vibratingTimer = 0;
				blockDrawn = false;
				TileEntityUtils.notifyUpdate(this);
			}

			if (vibrating && vibratingTimer++ >= maxVibratingTime)
			{
				vibrating = false;
				vibratingTimer = 0;
				TileEntityUtils.notifyUpdate(this);
			}

		}
		else if (inTransition)
		{
			vibrating = false;
			vibratingTimer = 0;

			if (powered) // powering => going invisible
			{
				transitionTimer++;
				if (transitionTimer >= getDuration())
				{
					inTransition = false;
					worldObj.spawnParticle(EnumParticleTypes.SMOKE_NORMAL,
							pos.getX() + 0.5F,
							pos.getY() + 0.5F,
							pos.getZ() + 0.5F,
							0.0F,
							0.0F,
							0.0F);
				}
			}
			else
			// shutting down => going visible
			{
				transitionTimer--;
				if (transitionTimer <= 0)
				{
					inTransition = false;
					TileEntityUtils.notifyUpdate(this);
				}
			}
		}
	}

	@Override
	@SuppressWarnings("deprecation")
	public void readFromNBT(NBTTagCompound nbt)
	{
		super.readFromNBT(nbt);
		if (nbt.hasKey("BlockID"))
		{
			Block block = Block.getBlockById(nbt.getInteger("BlockId"));
			copiedState = block.getStateFromMeta(nbt.getInteger("BlockMetadata"));
		}
		else
			copiedState = MBlockState.fromNBT(nbt);

		if (nbt.hasKey("copiedTileEntity"))
		{
			initCopiedTileEntity();
			copiedTileEntity.readFromNBT(nbt.getCompoundTag("copiedTileEntity"));
		}

		frameType = VanishingBlock.Type.values()[nbt.getInteger("FrameType")];
		powered = nbt.getBoolean("Powered");
		duration = nbt.getInteger("Duration");
		inTransition = nbt.getBoolean("InTransition");
		transitionTimer = nbt.getInteger("TransitionTimer");
		vibrating = nbt.getBoolean("Vibrating");
		vibratingTimer = nbt.getInteger("VibratingTimer");

	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		super.writeToNBT(nbt);
		if (copiedState != null)
		{
			MBlockState.toNBT(nbt, copiedState);
			if (copiedTileEntity != null)
			{
				NBTTagCompound teTag = new NBTTagCompound();
				copiedTileEntity.writeToNBT(teTag);
				nbt.setTag("copiedTileEntity", teTag);
			}
		}
		nbt.setInteger("FrameType", frameType.ordinal());
		nbt.setBoolean("Powered", powered);
		nbt.setInteger("Duration", getDuration());
		nbt.setBoolean("InTransition", inTransition);
		nbt.setInteger("TransitionTimer", transitionTimer);
		nbt.setBoolean("Vibrating", vibrating);
		nbt.setInteger("VibratingTimer", vibratingTimer);
		return nbt;
	}

	@Override
	public SPacketUpdateTileEntity getUpdatePacket()
	{
		NBTTagCompound nbt = new NBTTagCompound();
		this.writeToNBT(nbt);
		return new SPacketUpdateTileEntity(pos, 0, nbt);
	}

	@Override
	public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity packet)
	{
		this.readFromNBT(packet.getNbtCompound());
	}

	@Override
	public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newSate)
	{
		return oldState.getBlock() != newSate.getBlock();
	}

	@Override
	public boolean shouldRenderInPass(int pass)
	{
		return pass == 0;
	}
}
