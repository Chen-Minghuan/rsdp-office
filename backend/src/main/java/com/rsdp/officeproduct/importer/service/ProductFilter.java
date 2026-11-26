package com.rsdp.officeproduct.importer.service;

import java.util.List;

public record ProductFilter(List<String> levels, List<String> spaceTypes, List<String> productTypes,
                            List<String> materials, List<String> colors, List<String> widthRanges,
                            List<String> legTypes, List<String> storageTypes) {

    public ProductFilter {
        levels = List.copyOf(levels);
        spaceTypes = List.copyOf(spaceTypes);
        productTypes = List.copyOf(productTypes);
        materials = List.copyOf(materials);
        colors = List.copyOf(colors);
        widthRanges = List.copyOf(widthRanges);
        legTypes = List.copyOf(legTypes);
        storageTypes = List.copyOf(storageTypes);
    }

    public boolean matches(ProductFilterSupport.Attributes attributes) {
        return (levels.isEmpty() || levels.contains(attributes.productLevel()))
            && (spaceTypes.isEmpty() || spaceTypes.contains(attributes.spaceType()))
            && (productTypes.isEmpty() || productTypes.contains(attributes.productType()))
            && (colors.isEmpty() || attributes.colorNames().stream().anyMatch(colors::contains))
            && (legTypes.isEmpty() || attributes.legTypes().stream().anyMatch(legTypes::contains))
            && (storageTypes.isEmpty() || storageTypes.contains(attributes.storageType()))
            && matchesAnyVariant(attributes.variants());
    }

    private boolean matchesAnyVariant(List<ProductFilterSupport.Source.Variant> variants) {
        if (variants.isEmpty()) {
            return materials.isEmpty() && (widthRanges.isEmpty() || widthRanges.contains("UNSPECIFIED"));
        }
        return variants.stream().anyMatch(this::matchesVariant);
    }

    private boolean matchesVariant(ProductFilterSupport.Source.Variant variant) {
        boolean materialMatches = materials.isEmpty() || materials.contains(variant.materialText());
        return materialMatches && ProductFilterSupport.widthInRanges(variant.widthMm(), widthRanges);
    }
}
