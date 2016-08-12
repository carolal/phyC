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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.joptimizer.functions.ConvexMultivariateRealFunction;
import com.joptimizer.functions.LinearMultivariateRealFunction;
import com.joptimizer.functions.PDQuadraticMultivariateRealFunction;
import com.joptimizer.optimizers.JOptimizer;
import com.joptimizer.optimizers.OptimizationRequest;

import lineage.AAFClusterer.Cluster;
import util.Visualizer;
import edu.uci.ics.jung.graph.DirectedGraph;
import edu.uci.ics.jung.graph.DirectedSparseGraph;
import edu.uci.ics.jung.graph.util.EdgeType;


/**
 * PHYNetwork is a directed constraint graph representing the phylogenetic relationship
 * among sample sub-populations. It is a DAG.
 * Each internal node in the graph represents a sub-population.
 * A directed edge between two nodes denotes the 'happened-before' evolutionary 
 * relationship between the two nodes.
 * 
 * @autor viq
 */
public class PHYNetwork implements Serializable {
	private static final long serialVersionUID = 1L;
	
	/** Nodes in the graph divided by levels (number of samples node SNVs occurred in) */
	protected HashMap<Integer, ArrayList<PHYNode>> nodes;
	
	/** Nodes in the graph indexed by their unique ID */
	protected transient HashMap<Integer, PHYNode> nodesById;
	
	/** Adjacency map of nodes to the their neighbors/children */
	protected transient HashMap<PHYNode, ArrayList<PHYNode>> edges;
	
	/** Total number of nodes in the graph.
	 *  During construction: used as a counter to assign unique IDs to nodes */
	protected int numNodes;
	
	/** Total number of edges in the graph */
	protected int numEdges;
	
	/** Total number of tissue samples */
	protected int numSamples;
		
	private static Logger logger = LineageEngine.logger;
	
	// ---- Network Construction ----
	
	/**
	 * Constructs a PHYNetwork from the sub-populations of the SNV groups
	 */
	public PHYNetwork(ArrayList<SNVGroup> groups, int totalNumSamples) {
		numSamples = totalNumSamples;
		numNodes = 0;
		nodes = new HashMap<Integer, ArrayList<PHYNode>>();
		nodesById = new HashMap<Integer, PHYNode>();
		edges = new HashMap<PHYNode, ArrayList<PHYNode>>(); 
	
		// add root node
		PHYNode root = new PHYNode(numSamples+1, numNodes);
		addNode(root, numSamples+1);
				
		// add group sub-population nodes
		for(SNVGroup g : groups) {
			PHYNode[] groupNodes = new PHYNode[g.getSubPopulations().length];
			for(int i = 0; i < groupNodes.length; i++) {
				PHYNode node = new PHYNode(g, i, g.getNumSamples(), numNodes);
				addNode(node, g.getNumSamples());
				groupNodes[i] = node;
			}
			// add edges between each group's sub-population nodes
			for(int i = 0; i < groupNodes.length; i++) {
				for(int j = i+1; j <  groupNodes.length; j++) {
					checkAndAddEdge(groupNodes[i], groupNodes[j]);
				}
			}
		}
		
		// add inter-level edges
		for(int i = numSamples + 1; i > 0; i--) {
			ArrayList<PHYNode> fromLevelNodes = nodes.get(i);
			if(fromLevelNodes == null) continue;
			// find the next non-empty level
			int j = i-1;
			ArrayList<PHYNode> toLevelNodes = nodes.get(j);
			while((toLevelNodes == null) && (j > 0)) {
				j--;
				toLevelNodes = nodes.get(j);
			}
			if(toLevelNodes == null) continue;
			for(PHYNode n1 : fromLevelNodes) {
				for(PHYNode n2: toLevelNodes) {
					checkAndAddEdge(n1, n2);
				}
			}
		}
		
		if(Parameters.ALL_EDGES) {
			addAllHiddenEdges();
		}
		
		// find the nodes that are not connected and connect them to a valid node in the closest higher level
		int[] nodeMask = new int[numNodes];
		for(PHYNode n : edges.keySet()) {
			for(PHYNode m : edges.get(n)) {
				nodeMask[m.getNodeId()] = 1;
			}
		}
		
		// skips the root
		for(int i = 1; i < nodeMask.length; i++) {
			if(nodeMask[i] == 0) {
				PHYNode n = nodesById.get(i);			
				// find a parent in the closest higher level
				boolean found = false;
				for(int j = n.getLevel() + 2; j <= numSamples + 1; j++) {
					ArrayList<PHYNode> fromLevelNodes = nodes.get(j);
					if(fromLevelNodes == null) continue;
					for(PHYNode n2 : fromLevelNodes) {
						if(checkAndAddEdge(n2, n) == 0) {
							// found a parent
							found = true;
							break;
						}
					}
					if(found) break;
				}
				if(!found) {
					addEdge(root, n);
				}
			}
		}
	}
	
	/**
	 * Checks if an edge should be added between two nodes in the network based on the AAF data.
	 * If yes, it adds the edge in the appropriate direction.
	 * The edge is added in the direction that minimizes the error
	 * @requires n1 to be at an equal or higher level than n2
	 * @param n1 - node 1
	 * @param n2 - node 2
	 */
	public int checkAndAddEdge(PHYNode n1, PHYNode n2) {	
		if(n2.isLeaf) {
			int sampleId = n2.getLeafSampleId();
			if(n1.getAAF(sampleId) > 0) {
				addEdge(n1, n2);
				return 0;
			}
			return -1;
		}
		
		int comp_12 = 0;
		int comp_21 = 0;
		double err_12 = 0;
		double err_21 = 0;
		for(int i = 0; i < numSamples; i++) {
			if((n1.getAAF(i) == 0) && (n2.getAAF(i) != 0)) break;
			comp_12 += (n1.getAAF(i) >= (n2.getAAF(i) - getAAFErrorMargin(n1, n2, i))) ? 1 : 0;
			if(n1.getAAF(i) < n2.getAAF(i)) {
				err_12 += n2.getAAF(i) - n1.getAAF(i);
			}
		}
		for(int i = 0; i < numSamples; i++) {
			if((n2.getAAF(i) == 0) && (n1.getAAF(i) != 0)) break;
			comp_21 += (n2.getAAF(i) >= (n1.getAAF(i) - getAAFErrorMargin(n2, n1, i))) ? 1 : 0;
			if(n2.getAAF(i) < n1.getAAF(i)) {
				err_21 += n1.getAAF(i) - n2.getAAF(i);
			}
		}
		if(comp_12 == numSamples) {
			if (comp_21 == numSamples) {
				if(err_12 < err_21) {
					addEdge(n1, n2);
					return 0;
				} else {
					addEdge(n2, n1);
					return 1;
				}
			} else {
				addEdge(n1, n2);
				return 0;
			}
		} else if(comp_21 == numSamples) {
			addEdge(n2, n1);
			return 1;
		}
		
		return -1;
	}
	
	/**
	 * Returns the AAF error margin on the edge between the from and to nodes
	 */
	protected static double getAAFErrorMargin(PHYNode from, PHYNode to, int i) {
		//if(Parameters.STATIC_ERROR_MARGIN) {
			//return Parameters.AAF_ERROR_MARGIN;
		//}
		
		double parentStdError;
		double childStdError;
		int parentSampleSize = 0;
		int childSampleSize = 0;
		if(from.isRoot()) {
			parentStdError = Parameters.VAF_ERROR_MARGIN;
		} else {
			parentSampleSize = from.getCluster().getMembership().size();
			parentStdError = 1.96*from.getStdDev(i)/Math.sqrt((double)parentSampleSize);
		}	
		if(to.isRoot()) {
			childStdError = Parameters.VAF_ERROR_MARGIN;
		} else {
			childSampleSize = to.getCluster().getMembership().size();
			childStdError = 1.96*to.getStdDev(i)/Math.sqrt((double)childSampleSize);
		}
		double standardError = parentStdError + childStdError;
		if(standardError > Parameters.VAF_ERROR_MARGIN) {
			return standardError;
		}
		return Parameters.VAF_ERROR_MARGIN;
	}
	
	/** Adds a new node to the graph */
	public void addNode(PHYNode node, int level) {
		ArrayList<PHYNode> nodeList = nodes.get(level);
		if(nodeList == null) {
			nodes.put(level, new ArrayList<PHYNode>());
		}
		nodes.get(level).add(node);
		nodesById.put(node.getNodeId(), node);
		numNodes++;
	}
	
	/** Adds a new edge to the graph */
	public void addEdge(PHYNode from, PHYNode to) {
		ArrayList<PHYNode> nbrs = edges.get(from);
		if(nbrs == null) {
			edges.put(from, new ArrayList<PHYNode>());
		}
		if(!edges.get(from).contains(to)) {
			edges.get(from).add(to);
			numEdges++;
		}
	}
	
	/** Removes an edge from the graph */
	public void removeEdge(PHYNode from, PHYNode to) {
		ArrayList<PHYNode> nbrs = edges.get(from);
		if(nbrs != null) {
			for(PHYNode n : nbrs) {
				if(n.equals(to)) {
					nbrs.remove(n);
					break;
				}
			}
		}
	}
	
	/** Adds all the inter-level edges */
	public void addAllHiddenEdges() {
		for(int i = numSamples+1; i > 0; i--) { // (-) the root
			ArrayList<PHYNode> fromLevelNodes = nodes.get(i);
			if(fromLevelNodes == null) continue;
			for(int j = i-1; j >= 1; j--) { // (-) private
				ArrayList<PHYNode> toLevelNodes = nodes.get(j);
				if(toLevelNodes == null) continue;
				for(PHYNode n1 : fromLevelNodes) {
					for(PHYNode n2: toLevelNodes) {
						checkAndAddEdge(n1, n2);
					}
				}
			}
		}
	}
	
	// ---- Network Adjustments ----
	
	/**
	 * The network needs to be adjusted when no valid spanning PHYTrees are found.
	 * Adjustments include: 
	 * - removing nodes corresponding to less robust clusters (smallest first)
	 */
	public PHYNetwork fixNetwork() {
		// reconstruct the network from clusters of robust groups only
		Set<SNVGroup> filteredGroups = new HashSet<SNVGroup>();
		Cluster toRemove = null;
		SNVGroup group = null;
		for(PHYNode n : nodesById.values()) {
			Cluster c = n.getCluster();
			SNVGroup g = n.snvGroup;
			if(g != null) {
				if(!filteredGroups.contains(n.snvGroup)) {
					filteredGroups.add(n.snvGroup);
				}
				// check if this cluster is robust
				if((!c.isRobust())) {
					if (toRemove == null) {
						toRemove = c;
						group = g;
					} else if(c.getMembership().size() < toRemove.getMembership().size()) { // smallest
						toRemove = c;
						group = g;
					}
				}
			}
		}
		if(toRemove != null) {
			group.removeCluster(toRemove);
			logger.log(Level.INFO, "Removed cluster " + toRemove.getId() + " of group " + group.getTag() + " of size " + toRemove.getMembership().size() + " with members: ");
			for(Integer snv : toRemove.getMembership()) {
				SNVEntry entry = group.getSNVs().get(snv);
				logger.log(Level.INFO, entry.toString());
			}
		}
		return new PHYNetwork(new ArrayList<SNVGroup>(filteredGroups), numSamples);
	}
	
	/** 
	 * Collapses two cluster nodes and reconstructs the network
	 * @requires the two nodes are in the same group
	 */
	public PHYNetwork collapseClusterNodes(PHYNode n1, PHYNode n2) {	
		SNVGroup g = n1.getSNVGroup();
		Cluster c1 = n1.getCluster();
		Cluster c2 = n2.getCluster();
		
		// collapse c1 and c2
		Cluster union = new AAFClusterer().new Cluster(c1.getCentroid().clone(), new ArrayList<Integer>(c1.getMembership()), c1.getId());
		union.setStdDev(c1.getStdDev());
		for(Integer obs : c2.getMembership()) {
			union.addMember(obs);
		}
		union.recomputeCentroidAndStdDev(g.alleleFreqBySample, g.getSNVs().size(), g.getNumSamples());
		
		SNVGroup newG = null;
		Set<SNVGroup> groups = new HashSet<SNVGroup>();
		for(PHYNode n : nodesById.values()) {
			SNVGroup group = n.getSNVGroup();
			if(group != null) {
				if(!group.equals(g)) {
					if(!groups.contains(g)) {
						groups.add(n.getSNVGroup());	
					}
				} else {
					if(newG == null) {
						newG = new SNVGroup(g.getTag(), g.getSNVs(), g.isRobust());
						newG.subPopulations = g.subPopulations.clone();
						newG.removeCluster(c1);
						newG.removeCluster(c2);
						newG.addCluster(union);
						groups.add(newG);
					}
				}
			}
		}
		return new PHYNetwork(new ArrayList<SNVGroup>(groups), numSamples);
	}
	
	/** Removes a node and reconstructs the network */
	public PHYNetwork removeNode(PHYNode node) {
		SNVGroup newG = null;
		Set<SNVGroup> groups = new HashSet<SNVGroup>();
		for(PHYNode n : nodesById.values()) {
			SNVGroup g = n.getSNVGroup();
			if(g != null) {
				if(g.equals(node.getSNVGroup())) {
					if(newG == null) {
						newG = new SNVGroup(g.getTag(), g.getSNVs(), g.isRobust());
						newG.setSubPopulations(g.subPopulations.clone());
						newG.removeCluster(node.getCluster());
						groups.add(newG);
					}
				} else {
					if(!groups.contains(n.getSNVGroup())) {
						groups.add(n.getSNVGroup());
					}
				}
			}
		}
		return new PHYNetwork(new ArrayList<SNVGroup>(groups), numSamples);
	}
	
	// ---- Spanning PHYTree Generation ----
	
	// based on the algorithm from Gabow & Myers '78
	
	/** List of all generated spanning trees */
	private transient ArrayList<PHYTree> spanningTrees;
	
	/** Stack of edges directed from vertices in tree T to vertices not in T */
	private transient ArrayList<PHYEdge> f;
	
	/** The last spanning tree output so far */
	private transient PHYTree L;
	
	private transient int numGrowCalls = 0;
	
	/**
	 * Finds all spanning trees rooted at r
	 */
	public void grow(PHYTree t) {
		numGrowCalls++;
		// if the tree t contains all the nodes, it is complete
		if(t.treeNodes.size() == numNodes) {
			L = t;
			spanningTrees.add(L.clone());
		} else {
			// list used to reconstruct the original F
			ArrayList<PHYEdge> ff = new ArrayList<PHYEdge>();
			
			boolean b = false;
			while(!b && (f.size() > 0)) {
				// new tree edge
				PHYEdge e = f.remove(f.size() - 1);
				PHYNode v = e.to;
				t.addNode(v);
				t.addEdge(e.from, v);
				
				//check if adding this node does not violate the constraint
				if(t.checkConstraint(e.from)) {
					// update f
					ArrayList<PHYEdge> edgesAdded = new ArrayList<PHYEdge>();
					ArrayList<PHYNode> vNbrs = edges.get(v);
					if(vNbrs != null) {
						for(PHYNode w : vNbrs) {
							if(!t.containsNode(w)) {
								PHYEdge vw = new PHYEdge(v, w);
								f.add(vw);
								edgesAdded.add(vw);
							}
						}
					}
				
					// remove (w,v) w in T from f
					ArrayList<PHYEdge> edgesRemoved = new ArrayList<PHYEdge>();
					for(int i = 0; i < f.size(); i++) {
						PHYEdge wv = f.get(i);
						if(t.containsNode(wv.from) && (wv.to.equals(v))) {
							edgesRemoved.add(wv);
						}
					}
					f.removeAll(edgesRemoved);
	
					if(numGrowCalls % 1000000 == 0 && numGrowCalls != 0) {
						System.out.println(numGrowCalls);
					}
					
					if(numGrowCalls >= Parameters.MAX_NUM_GROW_CALLS) {
						return;
					}
					
					// recurse
					grow(t);
					
					if(spanningTrees.size() == Parameters.MAX_NUM_TREES) {
						return;
					}
					
					// pop
					f.removeAll(edgesAdded);
				
					// restore
					f.addAll(edgesRemoved);
				}
				
				// remove e from T and G
				t.removeEdge(e.from, e.to);
				this.removeEdge(e.from, e.to);
				
				// add e to FF
				ff.add(e);
				
				// bridge test
				b = true;
				for(PHYNode w : this.edges.keySet()) {
					ArrayList<PHYNode> wNbrs = this.edges.get(w);
					if(wNbrs == null) continue;
					for(PHYNode n : wNbrs) {
						if(n.equals(v)) {
							// check if w is a descendant of v in L
							if((L == null) || (!L.isDescendent(v, w))) {
								b = false;
								break;
							}
						}
					}
					if(!b) break;
				}				
			}
			
			// pop from ff, push to f, add to G
			for(int i = ff.size()-1; i >=0; i--) {
				PHYEdge e = ff.get(i);
				f.add(e);
				this.addEdge(e.from, e.to);
			}
			ff.clear();
		}
	}
	
	/**
	 * Generates all the spanning trees from the constraint network
	 * that pass the AAF constraints
	 */
	public ArrayList<PHYTree> getLineageTrees() {
		spanningTrees = new ArrayList<PHYTree>();
		
		// initialize tree t to contain the root
		PHYTree t = new PHYTree();
		PHYNode root = nodes.get(numSamples+1).get(0);
		t.addNode(root);
		// initialize f to contain all edges (root, v)
		f = new ArrayList<PHYEdge>();
		ArrayList<PHYNode> nbrs = edges.get(root);
		if(nbrs == null || nbrs.size() == 0) return spanningTrees;
		for(PHYNode n : nbrs) {
			f.add(new PHYEdge(root, n));
			
		}
		grow(t);
		//applyConsistencyConstraints(spanningTrees);
		return spanningTrees;
	}
	
	/**
	 * Applies the AAF constraints to all the spanning trees
	 * and removes the trees that don't pass the constraints
	 */
	/*private void applyAAFConstraints(ArrayList<PHYTree> trees) {
		ArrayList<PHYTree> toBeRemoved = new ArrayList<PHYTree>();
		for(PHYTree t : trees) {
			if(!checkAAFConstraints(t)) {
				toBeRemoved.add(t);
			}
		}
		spanningTrees.removeAll(toBeRemoved);
	}*/
	
	/**
	 * Returns true if the tree passes the AAF constraints
	 * @param t - spanning tree
	 */
	/*private boolean checkAAFConstraints(PHYTree t) {
		for(PHYNode n : t.treeEdges.keySet()) {
			ArrayList<PHYNode> nbrs = t.treeEdges.get(n);			
			for(int i = 0; i < numSamples; i++) {
				double affSum = 0;
				double errMargin = 0.0;
				for(PHYNode n2 : nbrs) {
					affSum += n2.getAAF(i);
					//errMargin += getAAFErrorMargin(n, n2, i);
				}
				errMargin = Parameters.AAF_ERROR_MARGIN;
				if(affSum > n.getAAF(i) + errMargin) {
					return false;
				}
			}
		}
		return true;
	}*/
	
	/**
	 * Applies the global consistency constraints to all the spanning trees
	 * and removes the trees that don't pass the constraints
	 */
	private void applyConsistencyConstraints(List<PHYTree> trees) {
		//ArrayList<PHYTree> toBeRemoved = new ArrayList<PHYTree>();
		for(int i = 0; i < trees.size(); i++) {
			if(!checkConsistencyConstraints(trees.get(i))) {
				//toBeRemoved.add(t);
				logger.info("Top tree " + i + " did not pass the QP consistency check");
			}
		}
		//spanningTrees.removeAll(toBeRemoved);
	}
	
	/**
	 * Returns true if the tree passes the consistency constraints
	 * @param t - spanning tree
	 */
	private boolean checkConsistencyConstraints(PHYTree t) {
		org.apache.log4j.BasicConfigurator.configure();
		org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.OFF);
			
		HashMap<PHYNode, Integer> nodeToIndex = new HashMap<PHYNode, Integer>();
		int numNodes = t.treeNodes.size();
		for(int i = 0; i < numNodes; i++) {
			nodeToIndex.put(t.treeNodes.get(i), i);
		}
		
		int nVariables = numNodes*numSamples;
		int nConstraints = nVariables + 2*nVariables + nVariables; // sum-of-child + abs val (2) + magnitude
		
		double[] diffVAFSCPerNode = new double[nConstraints]; 
		for(int i = 0; i < nConstraints; i++) {
			diffVAFSCPerNode[i] = 0.0001;
		}
		double[][] adjacency = new double[nConstraints][nVariables];
		
		// sum of children 
		for(int j = 0; j < numSamples; j++) {
			for(int i = 0; i < numNodes; i++) {
				PHYNode n = t.treeNodes.get(i);
				ArrayList<PHYNode> children = t.treeEdges.get(n);
				if(children == null) continue;
				diffVAFSCPerNode[i*numSamples + j] = n.getAAF(j);
				double sc = 0;
				for(PHYNode c : children) {
					sc += c.getAAF(j);
				}
				diffVAFSCPerNode[i*numSamples + j] -= sc;
				if(diffVAFSCPerNode[i*numSamples + j] == 0) {
					diffVAFSCPerNode[i*numSamples + j] = 0.0001;
				}
				for(PHYNode c : children) {
					int childIndex = nodeToIndex.get(c);
					adjacency[i*numSamples + j][numNodes*j + childIndex] = 1;
				}
				adjacency[i*numSamples + j][numNodes*j + i] = -1;
			}
		}
				
		// absolute value
		for(int i = 0; i < nVariables; i++) {
			adjacency[nVariables + i][i] = 1;
			adjacency[2*nVariables + i][i] = -1;
			diffVAFSCPerNode[nVariables + i] = Parameters.VAF_ERROR_MARGIN;
			diffVAFSCPerNode[2*nVariables + i] = Parameters.VAF_ERROR_MARGIN;
		}
		
		// magnitude
		for(int j = 0; j < numSamples; j++) {
			for(int i = 0; i < numNodes; i++) {
				adjacency[3*nVariables + i*numSamples + j][numNodes*j + i] = 1;
				diffVAFSCPerNode[3*nVariables + i*numSamples + j] = t.treeNodes.get(i).getAAF(j);
				if(t.treeNodes.get(i).getAAF(j) == 0) {
					diffVAFSCPerNode[3*nVariables + i*numSamples + j] = 0.00001;
				}
			}
		}

		// identity
		double[][] p = new double[nVariables][nVariables];
		for(int i = 0; i < nVariables; i++) {
			for(int j = 0; j < nVariables; j++) {
				if(i == j) {
					p[i][j] = 1;
				}
			}
		}
		
		OptimizationRequest or = new OptimizationRequest();
		PDQuadraticMultivariateRealFunction f = new PDQuadraticMultivariateRealFunction(p, null, 0);
		ConvexMultivariateRealFunction[] ineq = new ConvexMultivariateRealFunction[nConstraints];
		for(int i = 0; i < nConstraints; i++) {
			ineq[i] = new LinearMultivariateRealFunction(adjacency[i], -diffVAFSCPerNode[i]);
		}

		or.setF0(f);
		or.setFi(ineq);
		or.setToleranceFeas(1.E-9);
		or.setTolerance(1.E-9);
		
		JOptimizer opt = new JOptimizer();
		opt.setOptimizationRequest(or);
		try {
			int r = opt.optimize();
			if(r != 0) return false;
			double[] epsilon = opt.getOptimizationResponse().getSolution();
			t.errorScore = 0;
			for(int i = 0; i < epsilon.length; i++) {
				t.errorScore += Math.pow(epsilon[i], 2);
			}
		} catch (Exception e) {
			return false;
		}
		return true;
	}
	
	/** 
	 * Evaluates the spanning trees by computing their error score
	 * and ranking them by this score (lowest error first)
	 */
	public void evaluateLineageTrees() {
		Collections.sort(spanningTrees);
		int numTreesToCheck = spanningTrees.size() <= Parameters.NUM_TREES_FOR_CONSISTENCY_CHECK ? spanningTrees.size() : Parameters.NUM_TREES_FOR_CONSISTENCY_CHECK;
		//int origSize = spanningTrees.size();
		applyConsistencyConstraints(spanningTrees.subList(0, numTreesToCheck));
		//int nRemoved = origSize - spanningTrees.size();
		//Collections.sort(spanningTrees.subList(0, numTreesToCheck - nRemoved));
	}
	
	/** Debugging only - tests that all the spanning trees found are different */
	protected void testSpanningTrees() {
		for(int i = 0; i < spanningTrees.size(); i++) {
			for(int j = i + 1; j < spanningTrees.size(); j++) {
				PHYTree t1 = spanningTrees.get(i);
				PHYTree t2 = spanningTrees.get(j);
				
				// compare
				boolean sameEdges = true;
				for(PHYNode n1 : t1.treeEdges.keySet()) {
					for(PHYNode n2 : t1.treeEdges.get(n1)) {
						sameEdges &= t2.containsEdge(n1, n2);
					}
				}
				if(sameEdges) {
					System.out.println("Found same tree");
					System.out.println(t1);
					System.out.println(t2);
					return;
				}
			}
		}
		System.out.println("All trees are distinct");
	}
	
	// ---- Visualization ----
	
	/** Displays the constraint network graph */
	public void displayNetwork() {
		DirectedGraph<Integer, Integer> g = new DirectedSparseGraph<Integer, Integer>();
		HashMap<Integer, String> nodeLabels = new HashMap<Integer, String>();
			
		int edgeId = 0;
		for (PHYNode n : edges.keySet()) {
			g.addVertex(n.getNodeId());
			nodeLabels.put(n.getNodeId(), n.getLabel());
			for(PHYNode n2 : edges.get(n)) {
				if(!g.containsVertex(n2.getNodeId())) {
					g.addVertex(n2.getNodeId());
					nodeLabels.put(n2.getNodeId(), n2.getLabel());
				}
				g.addEdge(edgeId, n.getNodeId(), n2.getNodeId(), EdgeType.DIRECTED);
				edgeId++;
			}
		}
		Visualizer.showNetwork(g, nodeLabels);	
	}
	
	/** Displays a spanning tree of the network */
	public void displayTree(PHYTree t, ArrayList<String> sampleNames, HashMap<String, ArrayList<SNVEntry>> snvsByTag, String fileOutputName) {			
		DirectedGraph<Integer, Integer> g = new DirectedSparseGraph<Integer, Integer>();
		HashMap<Integer, String> nodeLabels = new HashMap<Integer, String>();
		HashMap<Integer, PHYNode> nodeObj = new HashMap<Integer, PHYNode>();
		
		int edgeId = 0;
		for (PHYNode n : t.treeEdges.keySet()) {
			g.addVertex(n.getNodeId());
			nodeLabels.put(n.getNodeId(), n.getLabel());
			nodeObj.put(n.getNodeId(), n);
			for(PHYNode n2 : t.treeEdges.get(n)) {
				if(!g.containsVertex(n2.getNodeId())) {
					g.addVertex(n2.getNodeId());
					nodeLabels.put(n2.getNodeId(), n2.getLabel());
					nodeObj.put(n2.getNodeId(), n2);
				}
				g.addEdge(edgeId, n.getNodeId(), n2.getNodeId(), EdgeType.DIRECTED);
				edgeId++;
			}
		}
		
		// add sample leaves
		for(int i = 0; i < numSamples; i++) {
			PHYNode n = new PHYNode(0, i, numNodes + i);
			g.addVertex(-n.getNodeId());
			nodeLabels.put(-n.getNodeId(), sampleNames.get(i));
			nodeObj.put(-n.getNodeId(), n);
			
			// find a parent in the closest higher level		 
			boolean found = false;
			ArrayList<PHYNode> parents = new ArrayList<PHYNode>();
			ArrayList<PHYNode> sameLevelParents = new ArrayList<PHYNode>();
			for(int j = n.getLevel() + 1; j <= numSamples; j++) {
				ArrayList<PHYNode> fromLevelNodes = nodes.get(j);
				if(fromLevelNodes == null) continue;
				for(PHYNode n2 : fromLevelNodes) {
					if(n2.getAAF(i) > 0) {
						boolean addEdge = true;
						for(PHYNode p : parents) {
							if(t.isDescendent(n2, p)) {
								addEdge = false;
								break;
							}
						}
						if(addEdge) {
							sameLevelParents.add(n2);
							parents.add(n2);
							found = true;
						}
					}
				}
				// remove nodes that are in same level that are connected
				ArrayList<PHYNode> toRemove = new ArrayList<PHYNode>();
				for(PHYNode n1 : sameLevelParents) {
					for(PHYNode n2 : sameLevelParents) {
						if(t.isDescendent(n1, n2)) {
							toRemove.add(n1);
						}
					}
				}
				sameLevelParents.removeAll(toRemove);
				
				for(PHYNode n2 : sameLevelParents) {
					g.addEdge(edgeId, n2.getNodeId(), -n.getNodeId());
					edgeId++;
				}
				sameLevelParents.clear();
			}
			if(!found) {
				g.addEdge(edgeId, 0, -n.getNodeId());
				edgeId++;
			}
		}			
		Visualizer.showLineageTree(g, nodeLabels, snvsByTag, fileOutputName, nodeObj, t, this, sampleNames);	
	}
	
	
	/**
	 * Returns a string representation of the graph
	 */
	public String toString() {
		String graph = "--- PHYLOGENETIC CONSTRAINT GRAPH --- \n";
		graph += "numNodes = " + numNodes + ", ";
		graph += "numEdges = " + numEdges + "\n";
		
		// print nodes by level
		graph += "NODES: \n";
		for(int i = numSamples + 1; i >= 0; i--) {
			graph += "level = " + i + ": \n";
			ArrayList<PHYNode> levelNodes = nodes.get(i);
			if(levelNodes != null) {
				for(PHYNode n : levelNodes) {
					graph += n.toString() + "\n";
				}
			}
		}
		graph += "EDGES: \n";
		for(PHYNode n1 : edges.keySet()) {
			ArrayList<PHYNode> nbrs = edges.get(n1);
			for(PHYNode n2 : nbrs) {
				graph += n1.getNodeId() + " -> " + n2.getNodeId() + "\n";
			}
		}
		
		return graph;
	}
	
	/**
	 * Returns a string representation of the graph
	 */
	public String getNodesAsString() {
		String s = "";
		for(int i = numSamples + 1; i >= 0; i--) {
			ArrayList<PHYNode> levelNodes = nodes.get(i);
			if(levelNodes != null) {
				for(PHYNode n : levelNodes) {
					if(n.isRoot()) continue;
					String tag = n.getSNVGroup().getTag();
					s += n.getNodeId() + "\t";
					s += tag + "\t";
					s += n.getCluster().getMembership().size() + "\t";
					double[] c = n.getCluster().getCentroid();
					DecimalFormat df = new DecimalFormat("#.##");
					int idx = 0;
					for(int j = 0; j < tag.length(); j++) {
						if(tag.charAt(j) == '1') {
							s += df.format(c[idx]) + "\t";
							idx++;
						} else {
							s += df.format(0) + "\t";
						}
					}
					s += "\n";
				}
			}
		}
		return s;
	}
	public String getNodesWithMembersAsString() {
		String s = "";
		for(int i = numSamples + 1; i >= 0; i--) {
			ArrayList<PHYNode> levelNodes = nodes.get(i);
			if(levelNodes != null) {
				for(PHYNode n : levelNodes) {
					if(n.isRoot()) continue;
					ArrayList<SNVEntry> snvs = n.getSNVs(n.getSNVGroup().getSNVs());
					s += n.getNodeId();
		    		s += "\t" + n.getSNVGroup().getTag();
		    		s += "\t[";
		    		DecimalFormat df = new DecimalFormat("#.##");
					for(int j = 0; j < n.getCluster().getCentroid().length; j++) {
						s += " " + df.format(n.getCluster().getCentroid()[j]);
					}
					s += "]";
		    		for(SNVEntry snv : snvs) {
		    			s += "\tsnv" + snv.getId();
		        	}
					s += "\n";
				}
			}
		}
		return s;
	}
	
	public String getNodeMembersOnlyAsString() {
		String s = "";
		for(int i = numSamples + 1; i >= 0; i--) {
			ArrayList<PHYNode> levelNodes = nodes.get(i);
			if(levelNodes != null) {
				for(PHYNode n : levelNodes) {
					if(n.isRoot()) continue;
					ArrayList<SNVEntry> snvs = n.getSNVs(n.getSNVGroup().getSNVs());
		    		for(SNVEntry snv : snvs) {
		    			s += "snv" + snv.getId() + ": " + snv.getChromosome() + " " + snv.getPosition() + " " + snv.getDescription() + "\n";
		        	}
				}
			}
		}
		return s;
	}
}
