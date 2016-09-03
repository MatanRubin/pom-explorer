package fr.lteconsulting.pomexplorer.rest;

import javax.ws.rs.ext.ContextResolver;
import javax.ws.rs.ext.Provider;

import com.fasterxml.jackson.databind.ObjectMapper;

@Provider
public class ObjectMapperResolver implements ContextResolver<ObjectMapper>
{
	@Override
	public ObjectMapper getContext( Class<?> type )
	{
		return ObjectMapperFactory.get();
	}
}