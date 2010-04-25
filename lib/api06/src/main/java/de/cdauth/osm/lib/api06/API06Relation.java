/*
    This file is part of the osmrmhv library.

    osmrmhv is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    osmrmhv is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with osmrmhv. If not, see <http://www.gnu.org/licenses/>.
*/

package de.cdauth.osm.lib.api06;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;

import de.cdauth.osm.lib.*;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import de.cdauth.osm.lib.GeographicalItem;

public class API06Relation extends API06GeographicalItem implements Relation
{
	protected API06Relation(Element a_dom, API06API a_api)
	{
		super(a_dom, a_api);
	}

	/**
	 * Returns an array of all members of this relation.
	 * @return
	 */
	
	public API06RelationMember[] getMembers()
	{
		NodeList members = getDOM().getElementsByTagName("member");
		API06RelationMember[] ret = new API06RelationMember[members.getLength()];
		for(int i=0; i<members.getLength(); i++)
			ret[i] = new API06RelationMember((Element) members.item(i), getAPI(), this);
		return ret;
	}
	
	/**
	 * Returns an array of all ways and nodes that are contained in this relation and all of its
	 * sub-relations. You may want to call downloadRecursive() first.
	 * @param a_date The date to use to fetch the members. Set to null to fetch the current member versions (which is a lot faster).
	 * @param a_members Set to null. Is filled with the result and passed along the recursive calls of this function.
	 * @param a_ignoreRelations Set to null. Is passed along the recursive calls to processing a relation twice and thus produce an infinite loop.
	 * @return A set of the members of this relation.
	 * @throws APIError 
	 */
	private HashSet<GeographicalItem> getMembersRecursive(Date a_date, HashSet<GeographicalItem> a_members, HashSet<ID> a_ignoreRelations) throws APIError
	{
		if(a_members == null)
			a_members = new HashSet<GeographicalItem>();
		if(a_ignoreRelations == null)
			a_ignoreRelations = new HashSet<ID>();
		a_ignoreRelations.add(getID());
		
		if(a_date == null)
			getAPI().getRelationFactory().downloadFull(getID());

		for(API06RelationMember it : getMembers())
		{
			Class<? extends GeographicalItem> type = it.getType();
			ID id = it.getReferenceID();
			if(type.equals(Way.class))
			{
				Way obj = (a_date == null ? getAPI().getWayFactory().fetch(id) : getAPI().getWayFactory().fetch(id, a_date));
				a_members.add(obj);
			}
			else if(type.equals(Node.class))
			{
				Node obj = (a_date == null ? getAPI().getNodeFactory().fetch(id) : getAPI().getNodeFactory().fetch(id, a_date));
				a_members.add(obj);
			}
			else if(type.equals(Relation.class) && !a_ignoreRelations.contains(id))
			{
				a_members.add(a_date == null ? getAPI().getRelationFactory().fetch(id) : getAPI().getRelationFactory().fetch(id, a_date));
				if(a_date == null)
					((API06Relation)getAPI().getRelationFactory().fetch(id)).getMembersRecursive(a_date, a_members, a_ignoreRelations);
				else
					((API06Relation)getAPI().getRelationFactory().fetch(id, a_date)).getMembersRecursive(a_date, a_members, a_ignoreRelations);
			}
		}
		return a_members;
	}
	
	@Override
	public GeographicalItem[] getMembersRecursive(Date a_date) throws APIError
	{
		return getMembersRecursive(a_date, null, null).toArray(new GeographicalItem[0]);
	}
	
	/**
	 * Returns an array of all ways that are contained in this relation and all of its sub-relations. You may want to call downloadRecursive() first.
	 * @return
	 * @throws APIError
	 */
	public Way[] getWaysRecursive(Date a_date) throws APIError
	{
		HashSet<GeographicalItem> members = getMembersRecursive(a_date, null, null);
		ArrayList<Way> ret = new ArrayList<Way>();
		for(GeographicalItem member : members)
		{
			if(member instanceof Way)
				ret.add((Way) member);
		}
		return ret.toArray(new Way[0]);
	}
	
	/**
	 * Returns an array of all nodes that are contained in this relation and all of its sub-relations. You may want to call downloadRecursive() first.
	 * @return
	 * @throws APIError 
	 */
	@Override
	public Node[] getNodesRecursive(Date a_date) throws APIError
	{
		HashSet<GeographicalItem> members = getMembersRecursive(a_date, null, null);
		ArrayList<Node> ret = new ArrayList<Node>();
		for(GeographicalItem member : members)
		{
			if(member instanceof Node)
				ret.add((Node) member);
		}
		return ret.toArray(new Node[0]);
	}
	
	public Relation[] getRelationsRecursive(Date a_date) throws APIError
	{
		HashSet<GeographicalItem> members = getMembersRecursive(a_date, null, null);
		ArrayList<Relation> ret = new ArrayList<Relation>();
		for(GeographicalItem member : members)
		{
			if(member instanceof Relation)
				ret.add((Relation) member);
		}
		return ret.toArray(new Relation[0]);
	}

	@Override
	public Segment[] getSegmentsRecursive(Date a_date) throws APIError
	{
		HashSet<Segment> ret = new HashSet<Segment>();

		Node[] nodes = getNodesRecursive(a_date);
		for(int i=0; i<nodes.length; i++)
			ret.add(new Segment(nodes[i], nodes[i]));
		
		Way[] ways = getWaysRecursive(a_date);
		for(int i=0; i<ways.length; i++)
		{
			Node[] members = ways[i].getMemberNodes(a_date);
			Node lastNode = null;
			for(int j=0; j<members.length; j++)
			{
				if(lastNode != null)
					ret.add(new Segment(lastNode, members[j]));
				lastNode = members[j];
			}
		}
		
		return ret.toArray(new Segment[0]);
	}
}