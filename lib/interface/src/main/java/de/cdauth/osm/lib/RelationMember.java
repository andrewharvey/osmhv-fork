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

package de.cdauth.osm.lib;

/**
 * Represents a member of a {@link Relation} in the OSM database. Relation members are references to
 * {@link GeographicalItem}s and have a role that is an arbitrary text. Relation members can be
 * contained multiple times in the same relation, even with the same role.
 * 
 * RelationMember objects are created by their corresponding {@link Relation} object. None of the
 * method throw an {@link APIError} because all information is filled in upon creation.
 * 
 * @author cdauth
 */
public interface RelationMember
{
	/**
	 * Returns the corresponding {@link Relation} that this member belongs to.
	 * @return The relation that contains this member.
	 */
	public Relation getRelation();
	
	/**
	 * Returns the type of the {@link GeographicalItem} that this member refers to.
	 * @return The class representing the type of {@link GeographicalItem} of this member.
	 */
	public Class<? extends GeographicalItem> getType();
	
	/**
	 * Returns the ID of the {@link GeographicalItem} that this member refers to.
	 * @return The ID this member refers to.
	 */
	public ID getReferenceID();
	
	/**
	 * Returns the role of this relation member.
	 * @return The role of this relation member, which might be an empty string.
	 */
	public String getRole();
}
