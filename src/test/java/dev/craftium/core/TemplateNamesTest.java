package dev.craftium.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TemplateNamesTest {
    @Test
    void aPlainFileKeepsItsNameWithoutTheExtension() {
        assertEquals("cottage", TemplateNames.fromPath("cottage.nbt"));
        assertEquals("big_tower", TemplateNames.fromPath("Big Tower.schem"));
    }

    @Test
    void foldersStayPartsOfTheName() {
        assertEquals("small/cottage", TemplateNames.fromPath("small/cottage.nbt"));
        assertEquals("houses/old_ones_2/well", TemplateNames.fromPath("Houses/Old Ones (2)/well.nbt"));
    }

    @Test
    void charactersANameCannotHoldBecomeUnderscores() {
        assertEquals("ch_teau/maison_d_t", TemplateNames.fromPath("Château/maison d'été.nbt"));
        assertEquals("blueprint", TemplateNames.fromPath("###.nbt"));
        assertEquals("a", TemplateNames.fromPath("__/a.nbt"));
    }
}
