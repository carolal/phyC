/*
 * Program LICHeE for multi-sample cancer phylogeny reconstruction
 * by Victoria Popic (viq@stanford.edu) 2014
 *
 * MIT License
 *
 * Copyright (c) 2014 Victoria Popic.
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 * BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
 * ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
*/


package lineage;

import java.io.Serializable;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;


/**
 * Spanning tree of the phylogenetic constraint network
 * 
 * @autor viq
 */
public class PHYTree implements Comparable<PHYTree>, Serializable {
	private static final long serialVersionUID = 1L;
	
	protected ArrayList<PHYNode> treeNodes;
	protected HashMap<PHYNode, ArrayList<PHYNode>> treeEdges;
	protected double errorScore = -1;
	
	public PHYTree() {
		treeNodes = new ArrayList<PHYNode>();
		treeEdges = new HashMap<PHYNode, ArrayList<PHYNode>>();
	}
	
	public void addNode(PHYNode n) {
		if(!treeNodes.contains(n)) {
			treeNodes.add(n);
		}
	}
	
	public void addEdge(PHYNode from, PHYNode to) {
		ArrayList<PHYNode> nbrs = treeEdges.get(from);
		if(nbrs == null) {
			treeEdges.put(from, new ArrayList<PHYNode>());
		}
		if(!treeEdges.get(from).contains(to)) {
			treeEdges.get(from).add(to);
		}
	}
	
	public void removeEdge(PHYNode from, PHYNode to) {
		ArrayList<PHYNode> nbrs = treeEdges.get(from);
		if(nbrs != null) {
			for(PHYNode n : nbrs) {
				if(n.equals(to)) {
					nbrs.remove(n);
					break;
				}
			}
		}
		
		// remove the node if no edge points to it
		boolean connected = false;
		for(PHYNode n : treeEdges.keySet()) {
			for(PHYNode n2 : treeEdges.get(n)) {
				if(to.equals(n2)) {
					connected = true;
					break;
				}
			}
		}
		if(!connected) {
			treeNodes.remove(to);
		}			
	}
	
	public boolean containsNode(PHYNode v) {
		for(PHYNode n : treeNodes) {
			if(n.equals(v)) {
				return true;
			}
		}
		return false;
	}
	
	public boolean containsEdge(PHYNode from, PHYNode to) {
		if(treeEdges.get(from) == null) return false;
		for(PHYNode n : treeEdges.get(from)) {
			if(n.equals(to)) {
				return true;
			}
		}
		return false;
	}
	
	/** 
	 * Returns a copy of the tree
	 */
	public PHYTree clone() {
		PHYTree copy = new PHYTree();
		copy.treeNodes.addAll(this.treeNodes);
		for(PHYNode n : this.treeEdges.keySet()) {
			ArrayList<PHYNode> nbrs = new ArrayList<PHYNode>();
			nbrs.addAll(this.treeEdges.get(n));
			copy.treeEdges.put(n, nbrs);
		}
		return copy;
	}
	
	/**
	 * Returns true if w is a descendant of v in this tree
	 */
	public boolean isDescendent(PHYNode v, PHYNode w) {
		ArrayList<PHYNode> nbrs = treeEdges.get(v);
		if(nbrs == null) {
			return false;
		}
		ArrayList<PHYNode> q = new ArrayList<PHYNode>(nbrs);
		while(q.size() > 0) {
			PHYNode n = q.remove(0);
			if(n.equals(w)) {
				return true;
			}
			if(treeEdges.get(n) != null) {
				q.addAll(treeEdges.get(n));
			}
		}
		return false;
	}
	
	public boolean checkConstraint(PHYNode n) {
		ArrayList<PHYNode> nbrs = treeEdges.get(n);			
		if(nbrs == null) return true;
				
		for(int i = 0; i < n.getNumSamples(); i++) {
			double affSum = 0;
			double errMargin = 0.0;
			for(PHYNode n2 : nbrs) {
				affSum += n2.getAAF(i);
				//errMargin += PHYNetwork.getAAFErrorMargin(n, n2, i);
			}
			errMargin = Parameters.VAF_ERROR_MARGIN;
			if(affSum > n.getAAF(i) + errMargin) {
				return false;
			}
		}
		
		return true;
	}
	
	public String toString() {
		String graph = "";
		for(PHYNode n1 : treeEdges.keySet()) {
			ArrayList<PHYNode> nbrs = treeEdges.get(n1);
			for(PHYNode n2 : nbrs) {
				graph += n1.getNodeId() + " -> " + n2.getNodeId() + "\n";
			}
		}
		return graph;
	}
	
	public String getNodeSNVString() {
		String s = "";
		for(PHYNode n : treeNodes) {
			if(n.getSNVGroup() == null) continue;
    		ArrayList<SNVEntry> snvs = n.getSNVs(n.getSNVGroup().getSNVs());
    		s += n.getNodeId();
    		s += "\t" + n.getSNVGroup().getTag();
    		for(SNVEntry snv : snvs) {
    			s += "\t" + snv.getDescription();
        	}
    		s += "\n";
		}
		return s;
	}
	
	
	/** 
	 * Returns the error score associated with the tree, 
	 * which is the sqrt of the sum of the children AAF sum deviation from the parent AAF
	 */
	public double getErrorScore() {
		if(errorScore == -1) {
			computeErrorScore();
		}
		return errorScore;
	}
	
	public double computeErrorScore() {
		ArrayList<PHYNode> nodes = new ArrayList<PHYNode>(treeEdges.keySet());
		Collections.sort(nodes);
		
		double err = 0;
		for(PHYNode n : nodes) {
			ArrayList<PHYNode> nbrs = treeEdges.get(n);			
			for(int i = 0; i < n.getNumSamples(); i++) {
				double affSum = 0;
				for(PHYNode n2 : nbrs) {
					affSum += n2.getAAF(i);
				}
				if(affSum > n.getAAF(i)) {
					err += Math.pow(affSum - n.getAAF(i), 2);
				}
			}
		}
		errorScore = Math.sqrt(err);
		return errorScore;
	}
	
	public int compareTo(PHYTree t) {
		return new Double(this.getErrorScore()).compareTo(t.getErrorScore());
	}
	
	/**
	 * Returns the sub-populations of a given sample
	 */
	public String getLineage(int sampleId, String sampleName) {
		StringBuilder lineage = new StringBuilder();
		String indent = "";
		lineage.append(sampleName + ":\n");
		lineage.append("GL\n");
		
		// traverse the tree starting from the root in DFS order
		for(PHYNode n : treeEdges.get(treeNodes.get(0))) {
			getLineageHelper(lineage, indent, n, sampleId);
		}
		return lineage.toString();
	}
	
	private void getLineageHelper(StringBuilder lineage, String indent, PHYNode n, int sampleId) {
		indent += "     ";			
		
		DecimalFormat df = new DecimalFormat("#.##");
		if(n.getSNVGroup().containsSample(sampleId)) {
			lineage.append(indent + n.getSNVGroup().getTag() + ": " + df.format(n.getAAF(sampleId)) + " [" + df.format(n.getStdDev(sampleId)) + "]\n");
		}
		if(treeEdges.get(n) != null) {
			for(PHYNode nbr : treeEdges.get(n)) {
				getLineageHelper(lineage, indent, nbr, sampleId);
			}
		}
	}
}	
