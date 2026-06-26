package xyz.bluspring.cpmmodelexport.mixin;

import com.google.gson.JsonArray;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.model.RenderedCube;
import com.tom.cpm.shared.parts.IModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

@Mixin(ModelDefinition.class)
public interface ModelDefinitionAccessor {
    @Accessor
    List<RenderedCube> getCubes();
}
