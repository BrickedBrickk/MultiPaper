package puregero.multipaper;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MultiPaperChunkHandlerTestSuite {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void skipsPlacementCallbacksWhenMirroringAnUpdateIntoAnUnloadedLevelChunk() {
        LevelChunk chunk = mock(LevelChunk.class);
        BlockPos pos = new BlockPos(47, 37, 18);
        BlockState state = mock(BlockState.class);

        MultiPaperChunkHandler.setBlockInUnloadedChunk(chunk, pos, state);

        verify(chunk).setBlockState(pos, state, Block.UPDATE_NONE | Block.UPDATE_SKIP_ON_PLACE);
    }

    @Test
    void keepsProtoChunkMirroringFreeOfBlockPlacementCallbacks() {
        ChunkAccess chunk = mock(ChunkAccess.class);
        BlockPos pos = new BlockPos(47, 37, 18);
        BlockState state = mock(BlockState.class);

        MultiPaperChunkHandler.setBlockInUnloadedChunk(chunk, pos, state);

        verify(chunk).setBlockState(pos, state, Block.UPDATE_NONE);
    }
}
