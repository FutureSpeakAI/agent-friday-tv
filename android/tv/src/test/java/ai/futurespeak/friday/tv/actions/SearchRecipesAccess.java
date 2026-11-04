package ai.futurespeak.friday.tv.actions;

import java.util.List;

/** Test access to the search recipe list (package-private in main). */
public final class SearchRecipesAccess {
    private SearchRecipesAccess() {}
    public static List<String> packages() { return SearchRecipes.PACKAGES; }
}
