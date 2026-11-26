package com.frontiermilitary.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** Прожектор: горит (свет 15), когда на него подан сигнал красного камня — например от полевого генератора. */
public class FloodlightBlock extends FacingBlock {
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public FloodlightBlock(Properties properties) {
        super(properties, 3, 0, 3, 13, 16, 10);
        this.registerDefaultState(this.defaultBlockState().setValue(LIT, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        return state == null ? null : state.setValue(LIT, context.getLevel().hasNeighborSignal(context.getClickedPos()));
    }

    @Override
    @SuppressWarnings("deprecation")
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean moving) {
        if (!level.isClientSide) {
            boolean powered = level.hasNeighborSignal(pos);
            if (powered != state.getValue(LIT)) {
                level.setBlock(pos, state.setValue(LIT, powered), 2);
            }
        }
    }
}
