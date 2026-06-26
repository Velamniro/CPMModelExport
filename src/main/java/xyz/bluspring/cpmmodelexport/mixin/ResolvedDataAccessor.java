package xyz.bluspring.cpmmodelexport.mixin;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.animation.Animation;
import com.tom.cpm.shared.animation.IPose;
import com.tom.cpm.shared.animation.interpolator.InterpolatorType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.tom.cpm.shared.parts.ModelPartAnimation$ResolvedData")
public interface ResolvedDataAccessor {
    @Accessor
    IPose getPose();

    @Accessor
    int getGid();

    @Accessor
    String getName();

    @Accessor
    int[] getComponents();

    @Accessor
    Vec3f[][] getPos();

    @Accessor
    Vec3f[][] getRot();

    @Accessor
    Vec3f[][] getScale();

    @Accessor
    Vec3f[][] getColor();

    @Accessor
    Boolean[][] getShow();

    @Accessor
    int getFrames();

    @Accessor
    int getDuration();

    @Accessor
    Animation getAnim();

    @Accessor
    boolean isLoop();

    @Accessor
    boolean isAdd();

    @Accessor
    int getPriority();

    @Accessor
    InterpolatorType getIt();

    @Accessor
    byte getDefaultValue();

    @Accessor
    int getOrder();

    @Accessor
    boolean isIsProperty();

    @Accessor
    String getGroup();

    @Accessor
    boolean isCommand();

    @Accessor
    boolean isLayerCtrl();

    @Accessor
    boolean isFinish();

    @Accessor
    byte getMaxValue();

    @Accessor
    boolean isInterpolateVal();

    @Accessor
    boolean isButtonHidden();
}
