package xyz.bluspring.cpmmodelexport.mixin;

import com.tom.cpm.shared.animation.Animation;
import com.tom.cpm.shared.animation.IModelComponent;
import com.tom.cpm.shared.animation.interpolator.Interpolator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Animation.class)
public interface AnimationAccessor {
    @Accessor
    Interpolator[][] getPsfs();

    @Accessor
    IModelComponent[] getComponentIDs();

    @Accessor
    boolean getAdd();

    @Accessor
    int getFrames();

    @Accessor
    Boolean[][] getShow();

    @Accessor
    int getDuration();

    @Accessor
    int getPriority();
}
