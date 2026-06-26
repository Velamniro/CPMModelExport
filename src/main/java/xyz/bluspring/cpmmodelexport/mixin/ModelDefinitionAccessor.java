package xyz.bluspring.cpmmodelexport.mixin;

import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.model.RenderedCube;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(ModelDefinition.class)
public interface ModelDefinitionAccessor {
    @Accessor
    List<RenderedCube> getCubes();
}
