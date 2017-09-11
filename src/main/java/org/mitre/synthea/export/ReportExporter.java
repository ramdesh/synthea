package org.mitre.synthea.export;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.modules.Generator;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.gson.stream.JsonWriter;

/**
 * The ReportExporter generates a "report" of the current run of Synthea,
 * tracking information on heathcare Outcomes, Access, and Costs.
 * The report is created in JSON so it can be easily parsed by other tools.
 */
public class ReportExporter 
{
	public static void export(Generator generator) 
	{
		if (generator == null || generator.database == null)
		{
			return;
		}
		
		try (Connection connection = generator.database.getConnection()) 
		{	
			File outDirectory = Exporter.getOutputFolder("statistics", null);
			String timeStamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
			Path outFilePath = outDirectory.toPath().resolve("statistics-"+timeStamp+".json");
			
			JsonWriter writer = new JsonWriter(new FileWriter(outFilePath.toFile()));
			writer.setIndent("  ");
			writer.beginObject(); // top-level
			
			reportParameters(writer);
			processOutcomes(connection, writer);
			processAccess(connection, writer);
			processCosts(connection, writer);
			
			writer.endObject(); // top-level	
			writer.close();
			
		} catch (IOException | SQLException e) {
			e.printStackTrace();
			throw new RuntimeException("error exporting statistics");
		}
	}
	
	private static void reportParameters(JsonWriter writer) throws IOException
	{
		writer.name("run-parameters").beginObject();
		for (String key : Config.allPropertyNames())
		{
			writer.name(key).value(Config.get(key, ""));
		}
		writer.endObject(); // run-parameters
	}
	
	private static void processOutcomes(Connection connection, JsonWriter writer) throws IOException, SQLException
	{
		PreparedStatement stmt = connection.prepareStatement("select year, AVG(qol) average, STDDEV_POP(qol) stddev, COUNT(qol) num from quality_of_life group by year order by year asc");
		// ASSUMPTION - there should never be a gap in years
		ResultSet rs = stmt.executeQuery();

		int firstYear = 0;
		
		// initial capacity is 80 - think 50 past 30 future?
		List<Double> averages = new ArrayList<Double>(80); 
		List<Double> stddevs = new ArrayList<Double>(80);
		List<Integer> counts = new ArrayList<Integer>(80);
		
		while (rs.next())
		{
			int year = rs.getInt(1);
			
			if (firstYear == 0)
			{
				firstYear = year;
			}
			
			double average = rs.getDouble(2);
			double stddev = rs.getDouble(3);
			int count = rs.getInt(4);
			
			averages.add(average);
			stddevs.add(stddev);
			counts.add(count);
		}

		writer.name("quality_of_life").beginObject();
		
		writer.name("first_year").value(firstYear);
		
		writer.name("averages").beginArray();
		for(double avg : averages)
		{
			writer.value(avg);
		}
		writer.endArray();
		
		writer.name("stddevs").beginArray();
		for(double stdev : stddevs)
		{
			writer.value(stdev);
		}
		writer.endArray();
		
		writer.name("counts").beginArray();
		for (int c : counts)
		{
			writer.value(c);
		}
		writer.endArray();
		
		writer.endObject(); // quality_of_life
	}
	
	private static void processAccess(Connection connection, JsonWriter writer) throws IOException, SQLException
	{
		writer.name("access").beginObject();
		
		writer.name("encounters").beginObject();
		
		Table<Integer, String, Integer> table = HashBasedTable.create();
		
		PreparedStatement stmt = connection.prepareStatement("select year, category, sum(value) as num from UTILIZATION_DETAIL group by year, category order by year asc");
		ResultSet rs = stmt.executeQuery();
		
		int firstYear = 0;
		int lastYear = 0;
		
		while (rs.next())
		{
			int year = rs.getInt(1);
			
			if (firstYear == 0)
			{
				firstYear = year;
			}
			lastYear = year;

			String category = rs.getString(2);
			
			if (category.contains("-"))
			{
				// it's a subcategory, ex "encounters-wellness" so split off the "encounters-" part (11 chars)
				category = category.substring(11);
			} else
			{
				category = "all-encounters";
			}
			
			int count = rs.getInt(3);
			
			table.put(year, category, count);
		}
		
		writer.name("first_year").value(firstYear);
		
		for (String encType : table.columnKeySet())
		{
			writer.name(encType).beginArray();

			for (int y = firstYear ; y <= lastYear ; y++)
			{
				Integer count = table.get(y, encType);
				
				if (count == null)
				{
					count = 0;
				}
				
				writer.value(count);
			}
			
			writer.endArray(); // encType
		}
		
		writer.endObject(); // encounters
		
		writer.endObject(); // access
	}
	
	private static void processCosts(Connection connection, JsonWriter writer) throws IOException, SQLException
	{
		writer.name("costs").beginObject();
		
		writer.endObject(); // access	
	}
}
