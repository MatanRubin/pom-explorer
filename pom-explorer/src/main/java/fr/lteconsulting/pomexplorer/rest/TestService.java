package fr.lteconsulting.pomexplorer.rest;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

@Path( "/products" )
public class TestService
{
	@GET
	@Produces( "application/json" )
	public TestDto get()
	{
		TestDto dto = new TestDto();
		dto.id = "e566jjh";
		dto.name = "toto";

		return dto;
	}
}
