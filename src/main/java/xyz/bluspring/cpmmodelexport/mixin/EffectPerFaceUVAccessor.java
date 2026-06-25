package xyz.bluspring.cpmmodelexport.mixin;

import com.tom.cpm.shared.effects.EffectPerFaceUV;
import com.tom.cpm.shared.model.render.PerFaceUV;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EffectPerFaceUV.class)
public interface EffectPerFaceUVAccessor {
    @Accessor
    PerFaceUV getUv();

    @Accessor
    int getId();
}
