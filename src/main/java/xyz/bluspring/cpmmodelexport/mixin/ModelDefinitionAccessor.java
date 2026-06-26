package xyz.bluspring.cpmmodelexport.mixin;

import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.parts.IModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(ModelDefinition.class)
public interface ModelDefinitionAccessor {
    @Accessor
    List<IModelPart> getParts();

    @Accessor
    List<IModelPart> getResolved();
}
