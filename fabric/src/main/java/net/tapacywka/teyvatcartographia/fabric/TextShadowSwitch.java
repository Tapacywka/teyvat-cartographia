package net.tapacywka.teyvatcartographia.fabric;

/**
 * The text shadow switch the EditBox mixin ({@code mixin.EditBoxMixin}) adds to every EditBox, on unless the mod
 * turns it off: NeoForge's {@code setTextShadow}, which vanilla 1.21.1 does not have.
 */
public interface TextShadowSwitch {
    void teyvatcartographia$setTextShadow(boolean shadow);
}
