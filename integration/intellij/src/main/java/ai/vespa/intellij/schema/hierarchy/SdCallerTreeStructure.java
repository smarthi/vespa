// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package ai.vespa.intellij.schema.hierarchy;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.ContainerUtil;
import ai.vespa.intellij.schema.psi.SdFirstPhaseDefinition;
import ai.vespa.intellij.schema.psi.SdFunctionDefinition;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * A Caller tree in the "Call Hierarchy" window.
 *
 * @author Shahar Ariel
 */
public class SdCallerTreeStructure extends SdCallTreeStructure {
    
    private HashMap<String, HashSet<PsiElement>> macroTreeChildren;
    
    public SdCallerTreeStructure(Project project, PsiElement element, String currentScopeType) {
        super(project, element, currentScopeType);
        macroTreeChildren = new HashMap<>();
    }
    
    @Override
    protected HashSet<PsiElement> getChildren(SdFunctionDefinition element) {
        return getCallers(element, macrosMap);
    }
    
    private HashSet<PsiElement> getCallers(SdFunctionDefinition macro, HashMap<String, List<PsiElement>> macrosMap) {
        String macroName = macro.getName();

        if (macroTreeChildren.containsKey(macroName)) {
            return macroTreeChildren.get(macroName);
        }
        
        HashSet<PsiElement> results = new HashSet<>();
        
        for (PsiElement macroImpl : macrosMap.get(macroName)) {
            SearchScope searchScope = getSearchScope(myScopeType, macroImpl);
            ReferencesSearch.search(macroImpl, searchScope).forEach((Consumer<? super PsiReference>) r -> {
                ProgressManager.checkCanceled();
                PsiElement psiElement = r.getElement();
                SdFunctionDefinition f = PsiTreeUtil.getParentOfType(psiElement, SdFunctionDefinition.class, false);
                if (f != null && f.getName() != null && !f.getName().equals(macroName)) { 
                    ContainerUtil.addIfNotNull(results, f); 
                } else {
                    SdFirstPhaseDefinition fp = PsiTreeUtil.getParentOfType(psiElement, SdFirstPhaseDefinition.class, false);
                    ContainerUtil.addIfNotNull(results, fp);
                }
            });
        }
        
        macroTreeChildren.put(macroName, results);
        return results;
    }
    
}
