/*
 * Copyright (c) 2017 Data and Web Science Group, University of Mannheim, Germany (http://dws.informatik.uni-mannheim.de/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package de.uni_mannheim.informatik.dws.winter.matching.blockers;

import java.util.ArrayList;
import java.util.List;

import de.uni_mannheim.informatik.dws.winter.matching.blockers.generators.BlockingKeyGenerator;
import de.uni_mannheim.informatik.dws.winter.model.Correspondence;
import de.uni_mannheim.informatik.dws.winter.model.DataSet;
import de.uni_mannheim.informatik.dws.winter.model.LeftIdentityPair;
import de.uni_mannheim.informatik.dws.winter.model.Matchable;
import de.uni_mannheim.informatik.dws.winter.model.Pair;
import de.uni_mannheim.informatik.dws.winter.processing.DatasetIterator;
import de.uni_mannheim.informatik.dws.winter.processing.PairFirstJoinKeyGenerator;
import de.uni_mannheim.informatik.dws.winter.processing.Processable;
import de.uni_mannheim.informatik.dws.winter.processing.ProcessableCollection;
import de.uni_mannheim.informatik.dws.winter.processing.RecordMapper;
import de.uni_mannheim.informatik.dws.winter.processing.aggregators.DistributionAggregator;
import de.uni_mannheim.informatik.dws.winter.utils.Distribution;
import de.uni_mannheim.informatik.dws.winter.utils.query.Q;

/**
 * Implementation of a standard {@link AbstractBlocker} based on blocking keys. All records for which the same blocking key is generated are returned as pairs.
 * 
 * @author Oliver Lehmberg (oli@dwslab.de)
 * 
 * @param <RecordType>
 * @param <SchemaElementType>
 * @param <BlockedType>
 * @param <CorrespondenceType>
 */
public class StandardBlocker<RecordType extends Matchable, SchemaElementType extends Matchable, BlockedType extends Matchable, CorrespondenceType extends Matchable>
	extends AbstractBlocker<RecordType, BlockedType, CorrespondenceType>
	implements Blocker<RecordType, SchemaElementType, BlockedType, CorrespondenceType>,
	SymmetricBlocker<RecordType, SchemaElementType, BlockedType, CorrespondenceType>
{

	private BlockingKeyGenerator<RecordType, CorrespondenceType, BlockedType> blockingFunction;
	private BlockingKeyGenerator<RecordType, CorrespondenceType, BlockedType> secondBlockingFunction;
	
	public StandardBlocker(BlockingKeyGenerator<RecordType, CorrespondenceType, BlockedType> blockingFunction) {
		this.blockingFunction = blockingFunction;
		this.secondBlockingFunction = blockingFunction;
	}
	
	/**
	 * 
	 * Creates a new Standard Blocker with the given blocking function(s). 
	 * If two datasets are used and secondBlockingFunction is not null, secondBlockingFunction will be used for the second dataset. If it is null, blockingFunction will be used for both datasets 
	 * 
	 * @param blockingFunction
	 * @param secondBlockingFunction
	 */
	public StandardBlocker(BlockingKeyGenerator<RecordType, CorrespondenceType, BlockedType> blockingFunction, BlockingKeyGenerator<RecordType, CorrespondenceType, BlockedType> secondBlockingFunction) {
		this.blockingFunction = blockingFunction;
		this.secondBlockingFunction = secondBlockingFunction == null ? blockingFunction : secondBlockingFunction;
	}

	/* (non-Javadoc)
	 * @see de.uni_mannheim.informatik.wdi.matching.blocking.Blocker#runBlocking(de.uni_mannheim.informatik.wdi.model.DataSet, de.uni_mannheim.informatik.wdi.model.DataSet, de.uni_mannheim.informatik.wdi.model.ResultSet, de.uni_mannheim.informatik.wdi.matching.MatchingEngine)
	 */
	@Override
	public Processable<Correspondence<BlockedType, CorrespondenceType>> runBlocking(
			DataSet<RecordType, SchemaElementType> dataset1,
			DataSet<RecordType, SchemaElementType> dataset2,
			Processable<Correspondence<CorrespondenceType, Matchable>> schemaCorrespondences){

		// combine the datasets with the schema correspondences
		Processable<Pair<RecordType, Processable<Correspondence<CorrespondenceType, Matchable>>>> ds1 = combineDataWithCorrespondences(dataset1, schemaCorrespondences, (r,c)->c.next(new Pair<>(r.getFirstRecord().getDataSourceIdentifier(),r)));
		Processable<Pair<RecordType, Processable<Correspondence<CorrespondenceType, Matchable>>>> ds2 = combineDataWithCorrespondences(dataset2, schemaCorrespondences, (r,c)->c.next(new Pair<>(r.getSecondRecord().getDataSourceIdentifier(),r)));
	
		// if we group the records by blocking key, we can obtain duplicates for BlockedType if it is different from RecordType and multiple records generated the same blocking key for BlockedType
		// so we aggregate the results to get a unique set of BlockedType elements (using the DistributionAggregator)
		
		// create the blocking keys for the first data set
		// results in pairs of [blocking key], distribution of correspondences
		Processable<Pair<String, Distribution<Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>>>>> grouped1 = 
				ds1.aggregateRecords(blockingFunction, new DistributionAggregator<String, Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>>, Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>>>() {

			private static final long serialVersionUID = 1L;

			@Override
			public Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>> getInnerKey(
					Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>> record) {
				// change the pairs such that they are considered equal if the first element is equal (ignoring the second element)
				return new LeftIdentityPair<>(record.getFirst(), record.getSecond());
			}

		});

		// create the blocking keys for the second data set
		Processable<Pair<String, Distribution<Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>>>>> grouped2 = 
				ds2.aggregateRecords(secondBlockingFunction, new DistributionAggregator<String, Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>>, Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>>>() {

			private static final long serialVersionUID = 1L;

			@Override
			public Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>> getInnerKey(
					Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>> record) {
				// change the pairs such that they are considered equal if the first element is equal (ignoring the second element)
				return new LeftIdentityPair<>(record.getFirst(), record.getSecond());
			}

		});
	
		// join the datasets via their blocking keys
		Processable<Pair<
		Pair<String,Distribution<Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>>>>,
		Pair<String,Distribution<Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>>>>>>
			blockedData = grouped1.join(grouped2, new PairFirstJoinKeyGenerator<>());
		
		// transform the blocks into pairs of records
		Processable<Correspondence<BlockedType, CorrespondenceType>> result = blockedData.transform(new RecordMapper<Pair<
				Pair<String,Distribution<Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>>>>,
				Pair<String,Distribution<Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>>>>>, 
				Correspondence<BlockedType, CorrespondenceType>>() {
			private static final long serialVersionUID = 1L;

			@Override
			public void mapRecord(
					Pair<
					Pair<String,Distribution<Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>>>>, 
					Pair<String,Distribution<Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>>>>> record,
					DatasetIterator<Correspondence<BlockedType, CorrespondenceType>> resultCollector) {
				
				// iterate over the left pairs [blocked element],[correspondences]
				for(Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>> p1 : record.getFirst().getSecond().getElements()){
					
					BlockedType record1 = p1.getFirst();
					
					// iterate over the right pairs [blocked element],[correspondences]
					for(Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>> p2 : record.getSecond().getSecond().getElements()){
						
						BlockedType record2 = p2.getFirst();
						
						Processable<Correspondence<CorrespondenceType, Matchable>> causes = 
								new ProcessableCollection<>(p1.getSecond())
								.append(p2.getSecond())
								.distinct();
						
						// filter the correspondences such that only correspondences between the two records are contained (by data source id)
						causes = causes.filter((c)->
							Q.toSet(p1.getFirst().getDataSourceIdentifier(), p2.getFirst().getDataSourceIdentifier())
							.equals(Q.toSet(c.getFirstRecord().getDataSourceIdentifier(), c.getSecondRecord().getDataSourceIdentifier()))
//							(c.getFirstRecord().getDataSourceIdentifier()==p1.getFirst().getDataSourceIdentifier() || c.getSecondRecord().getDataSourceIdentifier()==p1.getFirst().getDataSourceIdentifier())
//							&& (c.getFirstRecord().getDataSourceIdentifier()==p2.getFirst().getDataSourceIdentifier() || c.getSecondRecord().getDataSourceIdentifier()==p2.getFirst().getDataSourceIdentifier())
							);
						
						resultCollector.next(new Correspondence<BlockedType, CorrespondenceType>(record1, record2, 1.0, causes));
						
					}
					
				}
			}
		});
		
		return result;
	}

	/* (non-Javadoc)
	 * @see de.uni_mannheim.informatik.wdi.matching.blocking.Blocker#runBlocking(de.uni_mannheim.informatik.wdi.model.DataSet, boolean, de.uni_mannheim.informatik.wdi.model.ResultSet, de.uni_mannheim.informatik.wdi.matching.MatchingEngine)
	 */
	@Override
	public  Processable<Correspondence<BlockedType, CorrespondenceType>> runBlocking(
			DataSet<RecordType, SchemaElementType> dataset,
			Processable<Correspondence<CorrespondenceType, Matchable>> schemaCorrespondences) {

		// combine the datasets with the schema correspondences
//		Processable<Pair<RecordType, Processable<SimpleCorrespondence<CorrespondenceType>>>> ds = combineDataWithCorrespondences(dataset, schemaCorrespondences, (r,c)->c.next(new Pair<>(r.getFirstRecord().getDataSourceIdentifier(),r)));
		// as we only use one dataset here, we don't know if the record is on the left- or right-hand side of the correspondence
		Processable<Pair<RecordType, Processable<Correspondence<CorrespondenceType, Matchable>>>> ds = combineDataWithCorrespondences(dataset, schemaCorrespondences, 
				(r,c)->
				{
					c.next(new Pair<>(r.getFirstRecord().getDataSourceIdentifier(),r));
					c.next(new Pair<>(r.getSecondRecord().getDataSourceIdentifier(),r));
				});
		
		// if we group the records by blocking key, we can obtain duplicates for BlockedType if it is different from RecordType and multiple records generated the same blocking key for BlockedType
		// so we aggregate the results to get a unique set of BlockedType elements (using the DistributionAggregator)
		
		// group all records by their blocking keys		
		Processable<Pair<String, Distribution<Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>>>>> grouped = ds.aggregateRecords(blockingFunction, new DistributionAggregator<String, Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>>, Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>>>() {

			private static final long serialVersionUID = 1L;

			@Override
			public Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>> getInnerKey(
					Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>> record) {
				// change the pairs such that they are considered equal if the first element is equal (ignoring the second element)
				return new LeftIdentityPair<>(record.getFirst(), record.getSecond());
			}
		});
		
		// transform the groups into record pairs
		Processable<Correspondence<BlockedType, CorrespondenceType>> blocked = grouped.transform((g, collector) ->
		{
			List<Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>>> list = new ArrayList<>(g.getSecond().getElements());
			
			// sort the list before generating the pairs, so all pairs have the lower data source id on the left-hand side.
			list.sort((o1,o2)->Integer.compare(o1.getFirst().getDataSourceIdentifier(), o2.getFirst().getDataSourceIdentifier()));
			
			for(int i = 0; i < list.size(); i++) {
				Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>> p1 = list.get(i);
				for(int j = i+1; j < list.size(); j++) {
					Pair<BlockedType, Processable<Correspondence<CorrespondenceType, Matchable>>> p2 = list.get(j);
					
					Processable<Correspondence<CorrespondenceType, Matchable>> causes = new ProcessableCollection<>(p1.getSecond()).append(p2.getSecond()).distinct();
					
					// filter the correspondences such that only correspondences between the two records are contained (by data source id)
					causes = causes.filter((c)->
						(c.getFirstRecord().getDataSourceIdentifier()==p1.getFirst().getDataSourceIdentifier() || c.getSecondRecord().getDataSourceIdentifier()==p1.getFirst().getDataSourceIdentifier())
						&& (c.getFirstRecord().getDataSourceIdentifier()==p2.getFirst().getDataSourceIdentifier() || c.getSecondRecord().getDataSourceIdentifier()==p2.getFirst().getDataSourceIdentifier())
						);
					
					collector.next(new Correspondence<>(p1.getFirst(), p2.getFirst(), 1.0, causes));
				}
			}
		});
		
		// remove duplicates that were created if two records have multiple matching blocking keys
		blocked = blocked.distinct();
		
		return blocked;
	}

}
