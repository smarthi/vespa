// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package ai.vespa.intellij.schema.hierarchy;

import com.intellij.ide.hierarchy.HierarchyBrowserManager;
import com.intellij.ide.util.treeView.AlphaComparator;
import com.intellij.ide.util.treeView.NodeDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import ai.vespa.intellij.schema.SdUtil;
import ai.vespa.intellij.schema.psi.SdFile;
import ai.vespa.intellij.schema.psi.SdFunctionDefinition;
import ai.vespa.intellij.schema.psi.SdRankProfileDefinition;

import java.util.Comparator;
import java.util.HashSet;

/**
 * Call Hierarchy feature utilities.
 *
 * @author Shahar Ariel
 */
public class SdHierarchyUtil {
    
    private static final Comparator<NodeDescriptor<?>> NODE_DESCRIPTOR_COMPARATOR = Comparator.comparingInt(NodeDescriptor::getIndex);
    
    private SdHierarchyUtil() {
    }
    
    public static boolean isExecutable(PsiElement component) {
        return component instanceof SdFunctionDefinition;
    }
    
    public static HashSet<SdRankProfileDefinition> getRankProfileChildren(SdFile file, SdRankProfileDefinition rankProfileTarget) {
        HashSet<SdRankProfileDefinition> result = new HashSet<>();
        HashSet<SdRankProfileDefinition> notResult = new HashSet<>();

        for (SdRankProfileDefinition rank : PsiTreeUtil.collectElementsOfType(file, SdRankProfileDefinition.class)) {
            if (notResult.contains(rank)) {
                continue;
            }
            HashSet<SdRankProfileDefinition> tempRanks = new HashSet<>();
            SdRankProfileDefinition curRank = rank;
            while (curRank != null) {
                String curRankName = curRank.getName();
                if (curRankName != null && curRankName.equals(rankProfileTarget.getName())) {
                    result.addAll(tempRanks);
                    break;
                }
                tempRanks.add(curRank);
                PsiElement temp = SdUtil.getRankProfileParent(curRank);
                curRank = temp != null ? (SdRankProfileDefinition) temp : null;
            }
            if (curRank == null) {
                notResult.addAll(tempRanks);
            }
        }
        return result;
    }
    
    
    public static Comparator<NodeDescriptor<?>> getComparator(Project project) {
        final HierarchyBrowserManager.State state = HierarchyBrowserManager.getInstance(project).getState();
        if (state != null && state.SORT_ALPHABETICALLY) {
            return AlphaComparator.INSTANCE;
        }
        else {
            return NODE_DESCRIPTOR_COMPARATOR;
        }
    }
    
}
